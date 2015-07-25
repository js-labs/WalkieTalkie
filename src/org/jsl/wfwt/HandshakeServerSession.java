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
import org.jsl.collider.RetainableByteBuffer;
import org.jsl.collider.Session;
import org.jsl.collider.StreamDefragger;
import org.jsl.collider.TimerQueue;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.concurrent.TimeUnit;

public class HandshakeServerSession implements Session.Listener
{
    private static final String LOG_TAG = "HandshakeServerSession";

    private final String m_audioFormat;
    private final Channel m_channel;
    private final Session m_session;
    private final TimerQueue m_timerQueue;
    private final int m_pingInterval;
    private TimerHandler m_timerHandler;
    private StreamDefragger m_streamDefragger;

    private class TimerHandler implements Runnable
    {
        public void run()
        {
            Log.i( LOG_TAG, getLogPrefix() + "session timeout, close connection." );
            m_session.closeConnection();
        }
    }

    private String getLogPrefix()
    {
        return m_channel.getName() + " (" + m_session.getRemoteAddress() + "): ";
    }

    public HandshakeServerSession(
            String audioFormat, Channel channel, Session session, TimerQueue timerQueue, int pingInterval )
    {
        Log.i( LOG_TAG, channel.getName() + ": connection accepted: " +
                    session.getLocalAddress() + " -> " + session.getRemoteAddress() );

        m_audioFormat = audioFormat;
        m_channel = channel;
        m_session = session;
        m_timerQueue = timerQueue;
        m_pingInterval = pingInterval;

        if (pingInterval > 0)
        {
            m_timerHandler = new TimerHandler();
            m_timerQueue.schedule( m_timerHandler, pingInterval, TimeUnit.SECONDS );
        }

        m_streamDefragger = new StreamDefragger( Protocol.Message.HEADER_SIZE )
        {
            protected int validateHeader( ByteBuffer header )
            {
                if (BuildConfig.DEBUG && (header.remaining() < Protocol.Message.HEADER_SIZE))
                    throw new AssertionError();
                final int messageLength = Protocol.HandshakeRequest.getLength(header);
                if (messageLength < 0)
                {
                    m_session.closeConnection();
                    return -1; /* StreamDefragger.getNext() will return StreamDefragger.INVALID_HEADER */
                }
                return messageLength;
            }
        };
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
                    "invalid HandshakeRequest received, closing connection." );
        }
        else
        {
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
                    Thread.currentThread().interrupt();
                }
            }

            final short messageID = Protocol.Message.getID( msg );
            Log.d( LOG_TAG, getLogPrefix() + "received " + messageID );

            if (messageID == Protocol.HandshakeRequest.ID)
            {
                final short protocolVersion = Protocol.HandshakeRequest.getProtocolVersion(msg);
                if (protocolVersion == Protocol.VERSION)
                {
                    final RetainableByteBuffer msgNext = m_streamDefragger.getNext();
                    if (msgNext == null)
                    {
                        try
                        {
                            final String audioFormat = Protocol.HandshakeRequest.getAudioFormat( msg );
                            final AudioPlayer audioPlayer = AudioPlayer.create( audioFormat );
                            if (audioPlayer == null)
                            {
                                Log.i( LOG_TAG, getLogPrefix() +
                                        "unsupported audio format '" + audioFormat + "', closing connection." );
                                m_session.closeConnection();
                            }
                            else
                            {
                                final ChannelSession channelSession = new ChannelSession(
                                        m_channel, null, m_session, audioPlayer, m_timerQueue, m_pingInterval );
                                m_session.replaceListener( channelSession );

                                final ByteBuffer handshakeReply = Protocol.HandshakeReply.createOk( m_audioFormat );
                                m_session.sendData( handshakeReply );
                            }
                        }
                        catch (final CharacterCodingException ex)
                        {
                            Log.e( LOG_TAG, getLogPrefix() + ex.toString() );
                            m_session.closeConnection();
                        }
                    }
                    else
                    {
                        Log.i( LOG_TAG, getLogPrefix() +
                                "unexpected message " + Protocol.Message.getID(msgNext) +
                                "received, close connection." );
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
                        final ByteBuffer handshakeReply = Protocol.HandshakeReply.createFail( statusText );
                        m_session.sendData( handshakeReply );
                    }
                    catch (final CharacterCodingException ex)
                    {
                        Log.i( LOG_TAG, ex.toString() );
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
        }
    }

    public void onConnectionClosed()
    {
        Log.i( LOG_TAG, getLogPrefix() + "connection closed." );
        if (m_timerHandler != null)
        {
            try
            {
                m_timerQueue.cancel( m_timerHandler );
            }
            catch (final InterruptedException ex)
            {
                Log.i( LOG_TAG, ex.toString() );
                Thread.currentThread().interrupt();
            }
        }
        m_channel.removeSession( null, m_session );
    }
}
