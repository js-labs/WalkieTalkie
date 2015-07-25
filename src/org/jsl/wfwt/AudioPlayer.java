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
import java.nio.ByteBuffer;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

public abstract class AudioPlayer implements Runnable
{
    private static final String LOG_TAG = "AudioPlayer";

    private static final AtomicReferenceFieldUpdater<Node, Node> s_nodeNextUpdater
            = AtomicReferenceFieldUpdater.newUpdater( Node.class, Node.class, "next" );

    private static final AtomicReferenceFieldUpdater<AudioPlayer, Node> s_tailUpdater
            = AtomicReferenceFieldUpdater.newUpdater( AudioPlayer.class, Node.class, "m_tail" );

    private static class Node
    {
        public volatile Node next;
        public final RetainableByteBuffer audioFrame;

        public Node( RetainableByteBuffer audioFrame )
        {
            this.audioFrame = audioFrame;
        }
    }

    private static class PcmImpl extends AudioPlayer
    {
        private final AudioTrack m_audioTrack;

        public PcmImpl( String audioFormat, int sampleRate )
        {
            super( audioFormat );

            final int minBufferSize = AudioTrack.getMinBufferSize(
                    sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT );

            int bufferSize = (sampleRate * (Short.SIZE / Byte.SIZE) * 8);
            if (bufferSize < minBufferSize)
                bufferSize = minBufferSize;

            m_audioTrack = new AudioTrack(
                    AudioManager.STREAM_MUSIC,
                    sampleRate,
                    AudioFormat.CHANNEL_OUT_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                    AudioTrack.MODE_STREAM );

            m_thread.start();
        }

        public void run()
        {
            Log.i( LOG_TAG, "run [" + m_audioFormat + "]" );
            for (;;)
            {
                Node node = get();
                if (node.audioFrame == null)
                    break;

                m_audioTrack.play();
                do
                {
                    final ByteBuffer byteBuffer = node.audioFrame.getNioByteBuffer();
                    Log.i( LOG_TAG, "byteBuffer=" + byteBuffer.hashCode() + " " + byteBuffer );
                    //final int cc = (byteBuffer.remaining() / 2);
                    //for (int idx=0; idx<cc; idx++)
                    //    m_array[idx] = byteBuffer.getShort();
                    int rc = m_audioTrack.write( byteBuffer.array(), 0, byteBuffer.remaining() );
                    Log.i( LOG_TAG, "rc=" + rc );
                    node.audioFrame.release();
                    node = getNext();
                }
                while (node != null);

                m_audioTrack.stop();
            }

            m_audioTrack.release();
            Log.i( LOG_TAG, "run [" + m_audioFormat + "]: done" );
        }
    }

    protected final String m_audioFormat;
    protected final Thread m_thread;
    private final Semaphore m_sema;
    private Node m_head;
    private volatile Node m_tail;

    protected AudioPlayer( String audioFormat )
    {
        m_audioFormat = audioFormat;
        m_thread = new Thread( this, LOG_TAG );
        m_sema = new Semaphore(0);
    }

    protected final Node get()
    {
        try
        {
            m_sema.acquire();
        }
        catch (final InterruptedException ex)
        {
            Log.e( LOG_TAG, ex.toString() );
        }
        return m_head;
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

    public void write( RetainableByteBuffer audioFrame )
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

    public static AudioPlayer create( String audioFormat )
    {
        final String [] ss = audioFormat.split(":");
        try
        {
            if (ss.length > 0)
            {
                if ((ss[0].compareTo("PCM") == 0) && (ss.length > 1))
                {
                    final int sampleRate = Integer.parseInt( ss[1] );
                    return new PcmImpl( audioFormat, sampleRate );
                }
            }
        }
        catch (final NumberFormatException ex)
        {
            Log.e( LOG_TAG, ex.toString() );
        }
        return null;
    }
}
