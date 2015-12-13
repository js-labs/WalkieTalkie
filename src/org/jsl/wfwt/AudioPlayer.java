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

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.util.Log;
import org.jsl.collider.RetainableByteBuffer;
import org.jsl.collider.Session;

import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public abstract class AudioPlayer
{
    private static final String LOG_TAG = AudioPlayer.class.getSimpleName();

    private static final AtomicReferenceFieldUpdater<Node, Node> s_nodeNextUpdater
            = AtomicReferenceFieldUpdater.newUpdater( Node.class, Node.class, "next" );

    private static final AtomicReferenceFieldUpdater<Impl, Node> s_tailUpdater
            = AtomicReferenceFieldUpdater.newUpdater( Impl.class, Node.class, "m_tail" );

    private static class Node
    {
        public volatile Node next;
        public final RetainableByteBuffer audioFrame;

        public Node( RetainableByteBuffer audioFrame )
        {
            this.audioFrame = audioFrame;
        }
    }

    private static abstract class Impl extends AudioPlayer implements Runnable
    {
        protected final String m_logPrefix;
        protected final Thread m_thread;
        protected final Semaphore m_sema;
        protected Node m_head;
        private volatile Node m_tail;

        protected Impl( String logPrefix )
        {
            m_logPrefix = logPrefix;
            m_thread = new Thread( this, LOG_TAG );
            m_sema = new Semaphore(0);
        }

        protected final Node getNext()
        {
            final Node head = m_head;
            Node next = m_head.next;
            if (next == null)
            {
                m_head = null;
                if (s_tailUpdater.compareAndSet(this, head, null))
                    return null;
                while ((next = head.next) == null);
            }
            s_nodeNextUpdater.lazySet( head, null );
            m_head = next;
            return next;
        }

        public void play( RetainableByteBuffer audioFrame )
        {
            final Node node = new Node( audioFrame );
            audioFrame.retain();

            for (;;)
            {
                final Node tail = m_tail;
                if (BuildConfig.DEBUG && (tail != null) && (tail.audioFrame == null))
                {
                    audioFrame.release();
                    throw new AssertionError();
                }

                if (s_tailUpdater.compareAndSet(this, tail, node))
                {
                    if (tail == null)
                    {
                        m_head = node;
                        m_sema.release();
                    }
                    else
                        tail.next = node;
                    break;
                }
            }
        }

        public void stopAndWait()
        {
            final Node node = new Node( null );
            for (;;)
            {
                final Node tail = m_tail;
                if (BuildConfig.DEBUG && (tail != null) && (tail.next == null))
                    throw new AssertionError();

                if (s_tailUpdater.compareAndSet(this, tail, node))
                {
                    if (tail == null)
                    {
                        m_head = node;
                        m_sema.release();
                    }
                    else
                        tail.next = node;
                    break;
                }
            }

            try
            {
                m_thread.join();
            }
            catch (final InterruptedException ex)
            {
                Log.e( LOG_TAG, ex.toString() );
            }
        }
    }

    private static class PcmImpl extends Impl
    {
        private final AudioTrack m_audioTrack;
        private final Channel m_channel;
        private final String m_serviceName;
        private final Session m_session;
        private final int m_sampleSize;
        private final int m_sampleRate;

        public PcmImpl(
                String logPrefix,
                AudioTrack audioTrack,
                Channel channel,
                String serviceName,
                Session session )
        {
            super( logPrefix );
            m_audioTrack = audioTrack;
            m_channel = channel;
            m_serviceName = serviceName;
            m_session = session;
            m_sampleSize = ((m_audioTrack.getAudioFormat() == AudioFormat.ENCODING_PCM_8BIT) ? 1 : 2);
            m_sampleRate = m_audioTrack.getSampleRate();
            m_thread.start();
        }

        public void run()
        {
            Log.i( LOG_TAG, m_logPrefix + "run start" );
            for (;;)
            {
                try
                {
                    m_sema.acquire();
                }
                catch (final InterruptedException ex)
                {
                    Log.e( LOG_TAG, ex.toString() );
                    break;
                }

                if (m_head.audioFrame == null)
                    break;

                /* Sleep half frame size before start to reduce playback gaps. */

                long sleepTime = (m_head.audioFrame.remaining() / m_sampleSize / 2 * 1000 / m_sampleRate);
                try { Thread.sleep( sleepTime, 0 ); }
                catch (final InterruptedException ex) { Log.e( LOG_TAG, m_logPrefix + ex.toString() ); }

                m_channel.setSessionState( m_serviceName, m_session, 1 );

                int frames = 0;
                for (;;)
                {
                    final Node node = m_head;
                    if (node.audioFrame == null)
                    {
                        m_sema.release();
                        break;
                    }

                    final ByteBuffer byteBuffer = m_head.audioFrame.getNioByteBuffer();

                    /* We expect audio frame completely fill a byte buffer. */
                    if (BuildConfig.DEBUG && (byteBuffer.position() != 0))
                        throw new AssertionError();

                    final long startTime = System.currentTimeMillis();
                    final int bytes = m_audioTrack.write( byteBuffer.array(), byteBuffer.arrayOffset(), byteBuffer.remaining() );
                    final long writeTime = (System.currentTimeMillis() - startTime);
                    final long playTime = (byteBuffer.remaining() / m_sampleSize * 1000 / m_sampleRate);
                    Log.d( LOG_TAG, m_logPrefix + "write()=" + bytes + " writeTime=" + writeTime + " playTime=" + playTime );

                    if (frames++ == 0)
                    {
                        m_audioTrack.play();
                        m_audioTrack.stop();
                    }

                    if (writeTime < playTime)
                    {
                        sleepTime = (playTime - writeTime);
                        if (sleepTime > 100)
                        {
                            try { Thread.sleep( sleepTime-50, 0 ); }
                            catch (final InterruptedException ex) { Log.e( LOG_TAG, m_logPrefix + ex.toString() ); }
                        }
                    }

                    Node next = node.next;
                    if (next == null)
                    {
                        m_head = null;
                        if (s_tailUpdater.compareAndSet(this, node, null))
                        {
                            assert( node.next == null );
                            node.audioFrame.release();
                            break;
                        }
                        while ((next = node.next) == null);
                    }

                    node.audioFrame.release();
                    s_nodeNextUpdater.lazySet( node, null );

                    m_head = next;
                }

                m_channel.setSessionState( m_serviceName, m_session, 0 );
                Log.d( LOG_TAG, m_logPrefix + "frames=" + frames );
            }

            m_audioTrack.release();
            Log.i( LOG_TAG, m_logPrefix + "run done" );
        }
    }

    public static AudioPlayer create(
            String logPrefix,
            String audioFormat,
            Channel channel,
            String serviceName,
            Session session )
    {
        final String [] ss = audioFormat.split(":");
        try
        {
            if (ss.length > 0)
            {
                if ((ss[0].compareTo("PCM") == 0) && (ss.length > 1))
                {
                    final int sampleRate = Integer.parseInt( ss[1] );

                    final int minBufferSize = AudioTrack.getMinBufferSize(
                            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT );

                    int bufferSize = (sampleRate * (Short.SIZE / Byte.SIZE) * 10);
                    if (bufferSize < minBufferSize)
                        bufferSize = minBufferSize;

                    final AudioTrack audioTrack = new AudioTrack(
                            AudioManager.STREAM_MUSIC,
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            bufferSize,
                            AudioTrack.MODE_STREAM );

                    final String playerLogPrefix = (logPrefix + "/" + audioFormat + ": ");
                    Log.d( LOG_TAG, playerLogPrefix + "bufferSize=" + bufferSize );
                    return new PcmImpl( playerLogPrefix, audioTrack, channel, serviceName, session );
                }
            }
        }
        catch (final NumberFormatException ex)
        {
            Log.e( LOG_TAG, ex.toString() );
        }
        return null;
    }

    public abstract void play( RetainableByteBuffer audioFrame );
    public abstract void stopAndWait();
}
