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
import android.os.Process;
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
            = AtomicReferenceFieldUpdater.newUpdater( Node.class, Node.class, "next");

    private static final AtomicReferenceFieldUpdater<Impl, Node> s_tailUpdater
            = AtomicReferenceFieldUpdater.newUpdater( Impl.class, Node.class, "m_tail");

    private enum NodeCommand { NONE, BATCH_START, BATCH_END, STOP }

    private static class Node
    {
        public volatile Node next;
        final NodeCommand cmd;
        final RetainableByteBuffer audioFrame;

        Node(NodeCommand cmd, RetainableByteBuffer audioFrame)
        {
            this.cmd = cmd;
            this.audioFrame = audioFrame;
        }
    }

    private static abstract class Impl extends AudioPlayer implements Runnable
    {
        final String m_logPrefix;
        final Thread m_thread;
        final Semaphore m_sema;
        Node m_head;
        volatile Node m_tail;

        Impl(String logPrefix)
        {
            m_logPrefix = logPrefix;
            m_thread = new Thread(this, LOG_TAG);
            m_sema = new Semaphore(0);
        }

        private void enqueue(Node node)
        {
            for (;;)
            {
                final Node tail = s_tailUpdater.get(this);

                if (BuildConfig.DEBUG && (tail != null) && (tail.cmd == NodeCommand.STOP))
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
        }

        public void play(boolean batchStart, RetainableByteBuffer audioFrame)
        {
            final NodeCommand cmd = (batchStart ? NodeCommand.BATCH_START : NodeCommand.NONE);
            final Node node = new Node(cmd, audioFrame);
            audioFrame.retain();
            enqueue(node);
        }

        public void batchEnd()
        {
            final Node node = new Node(NodeCommand.BATCH_END, null);
            enqueue(node);
        }

        public void stopAndWait()
        {
            final Node node = new Node(NodeCommand.STOP, null);
            enqueue(node);

            boolean interrupted = false;
            try
            {
                m_thread.join();
            }
            catch (final InterruptedException ex)
            {
                Log.e(LOG_TAG, ex.toString());
                interrupted = true;
            }

            if (interrupted)
                Thread.currentThread().interrupt();
        }
    }

    private static class PcmImpl extends Impl
    {
        private final AudioTrack m_audioTrack;
        private final Channel m_channel;
        private final String m_serviceName;
        private final Session m_session;
        private final int m_bufferSize;

        PcmImpl(String logPrefix, AudioTrack audioTrack, Channel channel, String serviceName, Session session, int bufferSize)
        {
            super(logPrefix);
            m_audioTrack = audioTrack;
            m_channel = channel;
            m_serviceName = serviceName;
            m_session = session;
            m_bufferSize = bufferSize;
            m_thread.start();
        }

        public void run()
        {
            android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
            final int sampleSize = ((m_audioTrack.getAudioFormat() == AudioFormat.ENCODING_PCM_8BIT) ? 1 : 2);
            final byte [] silenceData = new byte[m_bufferSize*2];

            /*
            final double C = (m_audioTrack.getSampleRate() / 440.0);
            for (int idx=0; idx<silenceData.length/2; idx++)
            {
                double t = (idx / C * Math.PI);
                t = Math.sin(t) * Short.MAX_VALUE / 4.0;
                final byte v1 = (byte) (((int)t) >> 8);
                final byte v2 = (byte) (((int)t) & 0xFF);
                silenceData[idx*2] = v2;
                silenceData[idx*2+1] = v1;
            }
            */

            Log.i(LOG_TAG, m_logPrefix + "run start: bufferSize=" + m_bufferSize + " bytes"
                    + " (" + (m_bufferSize / (Short.SIZE / Byte.SIZE)) + " samples)");
            for (;;)
            {
                try
                {
                    m_sema.acquire();
                }
                catch (final InterruptedException ex)
                {
                    Log.e(LOG_TAG, ex.toString());
                    break;
                }

                Node node = m_head;
                if (node.cmd == NodeCommand.STOP)
                    break;

                if (BuildConfig.DEBUG && (node.cmd != NodeCommand.BATCH_START))
                    throw new AssertionError();

                Log.d(LOG_TAG, m_logPrefix + "play");
                m_channel.setSessionState(m_serviceName, m_session, 1);

                int samples = 0;
                int frames = 0;

                // warm up audio player with one silent block
                int bytes = m_audioTrack.write(silenceData, 0, silenceData.length);
                samples += (bytes / sampleSize);

                m_audioTrack.play();

                for (;;)
                {
                    if (node.cmd == NodeCommand.BATCH_END)
                    {
                        Node next = node.next;
                        if (next == null)
                        {
                            m_head = null;
                            if (s_tailUpdater.compareAndSet(this, node, null))
                            {
                                node = null;
                                break;
                            }
                            while (node.next == null);
                            next = node.next;
                        }
                        s_nodeNextUpdater.lazySet(node, null);
                        node = next;
                    }

                    if (node.cmd == NodeCommand.STOP)
                        break;

                    final ByteBuffer byteBuffer = node.audioFrame.getNioByteBuffer();
                    bytes = m_audioTrack.write(byteBuffer.array(), byteBuffer.arrayOffset(), byteBuffer.remaining());
                    if (bytes > 0)
                        samples += (bytes / sampleSize);

                    /*
                    Log.d(LOG_TAG, m_logPrefix
                            + "byteBuffer=" + byteBuffer.hashCode()
                            + " size=" + byteBuffer.remaining()
                            + " bytes=" + bytes
                            + " samples=" + samples);
                     */

                    node.audioFrame.release();
                    frames++;

                    Node next = node.next;
                    if (next == null)
                    {
                        m_head = null;
                        if (s_tailUpdater.compareAndSet(this, node, null))
                        {
                            // no data to write for now,
                            // play silence to avoid useless audio stop/start
                            for (;;)
                            {
                                bytes = m_audioTrack.write(silenceData, 0, silenceData.length);
                                samples += (bytes / sampleSize);
                                Log.d(LOG_TAG, "add silence, samples=" + samples);
                                if (m_sema.tryAcquire())
                                {
                                    Log.d(LOG_TAG, m_logPrefix + "tryAcquire()=true");
                                    node = m_head;
                                    break;
                                }
                            }
                        }
                        else
                        {
                            while (node.next == null);
                            next = node.next;
                            s_nodeNextUpdater.lazySet(node, null);
                            node = next;
                        }
                    }
                    else
                    {
                        s_nodeNextUpdater.lazySet(node, null);
                        node = next;
                    }
                }

                m_audioTrack.stop();
                m_channel.setSessionState(m_serviceName, m_session, 0);
                Log.d(LOG_TAG, m_logPrefix + "played " + frames + " frames, " + samples + " samples");

                if (node != null) // node.cmd == NodeCommand.STOP
                    break;
            }

            m_audioTrack.release();
            Log.i(LOG_TAG, m_logPrefix + "run done");
        }
    }

    static AudioPlayer create(
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
                    final int sampleRate = Integer.parseInt(ss[1]);

                    final int minBufferSize = AudioTrack.getMinBufferSize(
                            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);

                    final AudioTrack audioTrack = new AudioTrack(
                            AudioManager.STREAM_MUSIC,
                            sampleRate,
                            AudioFormat.CHANNEL_OUT_MONO,
                            AudioFormat.ENCODING_PCM_16BIT,
                            minBufferSize,
                            AudioTrack.MODE_STREAM);

                    final String playerLogPrefix = (logPrefix + "/" + audioFormat + ": ");
                    return new PcmImpl(playerLogPrefix, audioTrack, channel, serviceName, session, minBufferSize);
                }
            }
        }
        catch (final NumberFormatException ex)
        {
            Log.e(LOG_TAG, ex.toString());
        }
        return null;
    }

    public abstract void play(boolean batchStart, RetainableByteBuffer audioFrame);
    public abstract void batchEnd();
    public abstract void stopAndWait();
}
