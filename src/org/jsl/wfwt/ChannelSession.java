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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;

public class ChannelSession implements Session.Listener
{
    private static final String LOG_TAG = "ChannelSession";

    private static final AtomicIntegerFieldUpdater<ChannelSession>
            s_totalBytesReceivedUpdater = AtomicIntegerFieldUpdater.newUpdater(
                    ChannelSession.class, "m_totalBytesReceived" );

    private final Channel m_channel;
    private final String m_serviceName;
    private final Session m_session;
    private final StreamDefragger m_streamDefragger;
    private final SessionManager m_sessionManager;
    private final AudioPlayer m_audioPlayer;
    private final TimerQueue m_timerQueue;
    private TimerHandler m_timerHandler;

    private volatile int m_totalBytesReceived;
    private int m_lastBytesReceived;
    private int m_pingSent;

    private String getLogPrefix()
    {
        return m_channel.getName() + " " + m_session.getRemoteAddress();
    }

    private class TimerHandler implements Runnable
    {
        public void run()
        {
            handlePingTimeout();
        }
    }

    private void handlePingTimeout()
    {
        /* Send a ping message
         * only if we did not received any data for some time.
         */
        if (m_lastBytesReceived == m_totalBytesReceived)
        {
            if (++m_pingSent == 10)
            {
                Log.i( LOG_TAG, getLogPrefix() + "connection timeout, closing connection." );
                m_session.closeConnection();
            }
            else
            {
                Log.d( LOG_TAG, getLogPrefix() + "ping" );
                m_session.sendData( Protocol.Ping.create() );
            }
        }
        else
        {
            m_lastBytesReceived = m_totalBytesReceived;
            m_pingSent = 0;
        }
    }

    private void handleMessage( RetainableByteBuffer msg )
    {
        final short messageID = Protocol.Message.getID( msg );
        switch (messageID)
        {
            case Protocol.AudioFrame.ID:
                final RetainableByteBuffer audioFrame = Protocol.AudioFrame.getAudioData( msg );
                m_audioPlayer.write( audioFrame );
                audioFrame.release();
            break;

            case Protocol.Ping.ID:
                m_session.sendData( Protocol.Pong.create() );
            break;

            case Protocol.Pong.ID:
                /* do nothing */
            break;

            default:
                Log.w( LOG_TAG, getLogPrefix() + "unexpected message " + messageID );
            break;
        }
    }

    public static StreamDefragger createStreamDefragger( final Session session )
    {
        return new StreamDefragger( Protocol.Message.HEADER_SIZE )
        {
            protected int validateHeader( ByteBuffer header )
            {
                if (BuildConfig.DEBUG && (header.remaining() < Protocol.Message.HEADER_SIZE))
                    throw new AssertionError();
                final int messageLength = Protocol.Message.getLength(header);
                if (messageLength <= 0)
                    return -1; /* StreamDefragger.getNext() will return StreamDefragger.INVALID_HEADER */
                return messageLength;
            }
        };
    }

    public ChannelSession(
            Channel channel,
            String serviceName,
            Session session,
            StreamDefragger streamDefragger,
            SessionManager sessionManager,
            AudioPlayer audioPlayer,
            TimerQueue timerQueue,
            int pingInterval )
    {
        m_channel = channel;
        m_serviceName = serviceName;
        m_session = session;
        m_streamDefragger = streamDefragger;
        m_sessionManager = sessionManager;
        m_audioPlayer = audioPlayer;
        m_timerQueue = timerQueue;

        if (pingInterval > 0)
        {
            m_timerHandler = new TimerHandler();
            m_timerQueue.scheduleAtFixedRate(
                    m_timerHandler, pingInterval, pingInterval, TimeUnit.SECONDS );
        }

        m_sessionManager.addSession( this );

        // FIXME: check for possible message in the streamDefragger
    }

    public void onDataReceived( RetainableByteBuffer data )
    {
        final int bytesReceived = data.remaining();
        RetainableByteBuffer msg = m_streamDefragger.getNext( data );
        while (msg != null)
        {
            handleMessage( msg );
            msg = m_streamDefragger.getNext();
        }
        s_totalBytesReceivedUpdater.addAndGet( this, bytesReceived );
    }

    public void onConnectionClosed()
    {
        Log.i( LOG_TAG, getLogPrefix() + ": connection closed" );

        if (m_timerHandler != null)
        {
            try
            {
                m_timerQueue.cancel( m_timerHandler );
            }
            catch (final InterruptedException ex)
            {
                Log.w( LOG_TAG, ex.toString() );
                Thread.currentThread().interrupt();
            }
            m_timerHandler = null;
        }

        m_channel.removeSession( m_serviceName, m_session );
        m_sessionManager.removeSession( this );
    }

    public final int closeConnection()
    {
        return m_session.closeConnection();
    }

    public final int sendMessage( RetainableByteBuffer msg )
    {
        return m_session.sendData( msg );
    }
}
