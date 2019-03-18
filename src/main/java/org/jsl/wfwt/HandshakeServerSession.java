/*
 * Copyright (C) 2015 Sergey Zubarev, info@js-labs.org
 *
 * This file is a part of WiFi WalkieTalkie application.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jsl.wfwt;

import android.util.Log;
import org.jsl.collider.*;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.concurrent.TimeUnit;

public class HandshakeServerSession implements Session.Listener
{
    private static final String LOG_TAG = "HandshakeServerSession";

    private final String m_audioFormat;
    private final String m_stationName;
    private final Channel m_channel;
    private final Session m_session;
    private final StreamDefragger m_streamDefragger;
    private final SessionManager m_sessionManager;
    private final TimerQueue m_timerQueue;
    private final int m_pingInterval;
    private TimerHandler m_timerHandler;

    private class TimerHandler implements TimerQueue.Task
    {
        public long run()
        {
            Log.i(LOG_TAG, getLogPrefix() + "session timeout, close connection.");
            m_session.closeConnection();
            return 0;
        }
    }

    private String getLogPrefix()
    {
        return m_channel.getName() + " " + m_session.getRemoteAddress() + ": ";
    }

    HandshakeServerSession(
            String audioFormat,
            String stationName,
            Channel channel,
            Session session,
            SessionManager sessionManager,
            TimerQueue timerQueue,
            int pingInterval)
    {
        m_audioFormat = audioFormat;
        m_stationName = stationName;
        m_channel = channel;
        m_session = session;
        m_streamDefragger = ChannelSession.createStreamDefragger();
        m_sessionManager = sessionManager;
        m_timerQueue = timerQueue;
        m_pingInterval = pingInterval;
        if (pingInterval > 0)
        {
            m_timerHandler = new TimerHandler();
            m_timerQueue.schedule(m_timerHandler, pingInterval, TimeUnit.SECONDS);
        }

        Log.i(LOG_TAG, getLogPrefix() + "connection accepted");
    }

    public void onDataReceived( RetainableByteBuffer data )
    {
        final RetainableByteBuffer msg = m_streamDefragger.getNext( data );
        if (msg == null)
        {
            /* HandshakeRequest is fragmented, strange but can happen */
            Log.i( LOG_TAG, getLogPrefix() + "fragmented HandshakeRequest." );
        }
        else if (msg == StreamDefragger.INVALID_HEADER)
        {
            Log.i( LOG_TAG, getLogPrefix() +
                    "invalid <HandshakeRequest> received, close connection." );
            m_session.closeConnection();
        }
        else
        {
            boolean interrupted = false;

            if (m_timerHandler != null)
            {
                try
                {
                    if (m_timerQueue.cancel(m_timerHandler) != 0)
                    {
                        /* else timer fired, session is being closed,
                         * onConnectionClosed() will be called soon, do nothing here.
                         */
                        return;
                    }
                }
                catch (final InterruptedException ex)
                {
                    Log.w( LOG_TAG, ex.toString(), ex );
                    interrupted = true;
                }
            }

            final short messageID = Protocol.Message.getID( msg );
            if (messageID == Protocol.HandshakeRequest.ID)
            {
                final short protocolVersion = Protocol.HandshakeRequest.getProtocolVersion(msg);
                if (protocolVersion == Protocol.VERSION)
                {
                    try
                    {
                        final String audioFormat = Protocol.HandshakeRequest.getAudioFormat( msg );
                        final String stationName = Protocol.HandshakeRequest.getStationName( msg );
                        final AudioPlayer audioPlayer = AudioPlayer.create(
                                getLogPrefix(), audioFormat, m_channel, null, m_session );
                        if (audioPlayer == null)
                        {
                            Log.i( LOG_TAG, getLogPrefix() +
                                    "unsupported audio format '" + audioFormat + "', closing connection." );
                            m_session.closeConnection();
                        }
                        else
                        {
                            Log.i( LOG_TAG, getLogPrefix() + "handshake ok" );

                            /* Send reply first to be sure other side will receive
                             * HandshakeReplyOk before anything else.
                             */
                            final ByteBuffer handshakeReply = Protocol.HandshakeReplyOk.create( m_audioFormat, m_stationName );
                            m_session.sendData( handshakeReply );

                            final ChannelSession channelSession = new ChannelSession(
                                    m_channel, null, m_session, m_streamDefragger, m_sessionManager, audioPlayer, m_timerQueue, m_pingInterval );

                            m_channel.addSession( channelSession, stationName );
                            m_session.replaceListener( channelSession );
                        }
                    }
                    catch (final CharacterCodingException ex)
                    {
                        Log.e( LOG_TAG, getLogPrefix() + ex.toString(), ex );
                        m_session.closeConnection();
                    }
                }
                else
                {
                    /* Protocol version is different, can not continue. */
                    Log.i( LOG_TAG, getLogPrefix() + "protocol version mismatch: " +
                            Protocol.VERSION + "-" + protocolVersion + ", close connection." );

                    final String statusText = "Protocol version mismatch: " + Protocol.VERSION + "-" + protocolVersion;
                    try
                    {
                        final ByteBuffer handshakeReply = Protocol.HandshakeReplyFail.create( statusText );
                        m_session.sendData( handshakeReply );
                    }
                    catch (final CharacterCodingException ex)
                    {
                        Log.i( LOG_TAG, ex.toString(), ex );
                    }
                    m_session.closeConnection();
                }
            }
            else
            {
                Log.i( LOG_TAG, getLogPrefix() +
                       "unexpected message " + messageID + " received, closing connection." );
                m_session.closeConnection();
            }

            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    public void onConnectionClosed()
    {
        Log.i( LOG_TAG, getLogPrefix() + "connection closed" );
        boolean interrupted = false;
        if (m_timerHandler != null)
        {
            try
            {
                m_timerQueue.cancel( m_timerHandler );
            }
            catch (final InterruptedException ex)
            {
                Log.i( LOG_TAG, ex.toString(), ex );
                interrupted = true;
            }
        }
        m_streamDefragger.close();

        if (interrupted)
            Thread.currentThread().interrupt();
    }
}
