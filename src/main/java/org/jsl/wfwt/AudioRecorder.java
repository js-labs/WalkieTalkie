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

import android.content.res.Resources;
import android.media.*;
import android.os.Process;
import android.util.Log;
import org.jsl.collider.RetainableByteBuffer;
import org.jsl.collider.RetainableByteBufferCache;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

public class AudioRecorder implements Runnable
{
    private static final String LOG_TAG = "AudioRecorder";
    private static final Logger s_logger = Logger.getLogger("org.jsl.wfwt.AudioRecorder");

    private final SessionManager m_sessionManager;
    private final String m_audioFormat;
    private final AudioRecord m_audioRecord;
    private final int m_frameSize;

    private final Thread m_thread;
    private final ReentrantLock m_lock;
    private final Condition m_cond;
    private int m_state;
    private boolean m_ptt;
    private ByteBuffer m_rogerBeep;

    private static final int IDLE  = 0;
    private static final int START = 1;
    private static final int RUN   = 2;
    private static final int STOP  = 3;
    private static final int SHTDN = 4; // shutdown

    private static class SampleRateInfo
    {
        final int sampleRate;
        final int rogerBeepResourceId;

        SampleRateInfo(int sampleRate, int rogerBeepResourceId)
        {
            this.sampleRate = sampleRate;
            this.rogerBeepResourceId = rogerBeepResourceId;
        }
    };

    // According to the Android documentation 44100Hz is currently the only rate
    // that is guaranteed to work on all devices, but other rates such as 22050,
    // 16000, and 11025 may work on some device. It is better to transmit as low data
    // as possible,  so we will try to create recorder with rates starting from the lowest
    // while will not success.
    // We could have roger beep data with only highest sample rate and resample it
    // to the lower sample rate if we will be able to create a recorder with lower
    // sample rate, but resampling is not so simple, so let's have a resource
    // per each possible sample rate.
    private final static SampleRateInfo[] s_sampleRates = {
        new SampleRateInfo(11025, R.raw.roger_beep_11025),
        new SampleRateInfo(16000, R.raw.roger_beep_16000),
        new SampleRateInfo(22050, R.raw.roger_beep_22050),
        new SampleRateInfo(44100, R.raw.roger_beep_44100)
    };

    private static class SendBuffer
    {
        private final RetainableByteBufferCache cache;
        private RetainableByteBuffer byteBuffer;
        byte [] array;
        int arrayOffset;

        SendBuffer(int frameSize)
        {
            /* Use buffer large enough for 4 audio frame messages */
            cache = new RetainableByteBufferCache(
                    true, 4*Protocol.AudioFrame.getMessageSize(frameSize), Protocol.BYTE_ORDER, 8);
            byteBuffer = cache.get();
            array = byteBuffer.getNioByteBuffer().array();
            arrayOffset = byteBuffer.getNioByteBuffer().arrayOffset();
        }

        RetainableByteBuffer getBuffer(int messageSize)
        {
            final int position = byteBuffer.position();
            final int space = (byteBuffer.capacity() - position);
            if (space < messageSize)
            {
                byteBuffer.release();
                byteBuffer = cache.get();
                if (BuildConfig.DEBUG && (byteBuffer.position() != 0))
                    throw new AssertionError();
                array = byteBuffer.getNioByteBuffer().array();
                arrayOffset = byteBuffer.getNioByteBuffer().arrayOffset();
            }
            return byteBuffer;
        }

        void release()
        {
            byteBuffer.release();
            cache.clear(s_logger);
        }
    }

    private void send(RetainableByteBuffer byteBuffer, int position, int messageSize, boolean ptt)
    {
        final int limit = (position + messageSize);
        byteBuffer.position(position);
        byteBuffer.limit(limit);
        final RetainableByteBuffer msg = byteBuffer.slice();
        m_sessionManager.sendAudioFrame(msg, ptt); // FIXME?
        msg.release();
        byteBuffer.limit(byteBuffer.capacity());
        byteBuffer.position(limit);
    }

    private AudioRecorder(SessionManager sessionManager, AudioRecord audioRecord, String audioFormat, int frameSize)
    {
        m_sessionManager = sessionManager;
        m_audioRecord = audioRecord;
        m_audioFormat = audioFormat;
        m_frameSize = frameSize;
        m_thread = new Thread(this, LOG_TAG + " [" + audioFormat + "]");
        m_lock = new ReentrantLock();
        m_cond = m_lock.newCondition();
        m_state = IDLE;
        m_thread.start();
    }

    String getAudioFormat()
    {
        return m_audioFormat;
    }

    public void run()
    {
        final int frameSize = m_frameSize;
        Log.i(LOG_TAG, "run [" + m_audioFormat + "]: frameSize=" + frameSize);
        android.os.Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO);

        final SendBuffer sendBuffer = new SendBuffer(frameSize);
        boolean interrupted = false;
        boolean ptt;
        int frames = 0;
        try
        {
            for (;;)
            {
                m_lock.lock();
                try
                {
                    while (m_state == IDLE)
                        m_cond.await();

                    if (m_state == START)
                    {
                        m_audioRecord.startRecording();
                        m_state = RUN;
                    }
                    else if (m_state == STOP)
                    {
                        m_audioRecord.stop();
                        m_state = IDLE;

                        final ByteBuffer rogerBeep = m_rogerBeep;
                        if (rogerBeep != null)
                            m_sessionManager.sendAudioFrame(rogerBeep, /*ptt*/true);

                        final int messageSize = Protocol.AudioFrame.getMessageSize(0);
                        final RetainableByteBuffer byteBuffer = sendBuffer.getBuffer(messageSize);
                        final int position = byteBuffer.position();
                        Protocol.AudioFrame.init(byteBuffer.getNioByteBuffer(), /*batch start*/false, 0);
                        send(byteBuffer, position, messageSize, /*ptt*/true); // FIXME?
                        Log.i(LOG_TAG, "Sent " + frames + " frames, " + (frames*m_frameSize) + " bytes");
                        frames = 0;
                        continue;
                    }
                    else if (m_state == SHTDN)
                        break;

                    ptt = m_ptt;
                }
                finally
                {
                    m_lock.unlock();
                }

                final int messageSize = Protocol.AudioFrame.getMessageSize(frameSize);
                final RetainableByteBuffer byteBuffer = sendBuffer.getBuffer(messageSize);
                final int position = byteBuffer.position();

                Protocol.AudioFrame.init(byteBuffer.getNioByteBuffer(), /*batch start*/(frames == 0), frameSize);
                if (BuildConfig.DEBUG && (byteBuffer.remaining() < frameSize))
                    throw new AssertionError();

                final int bytesReady = m_audioRecord.read(
                        sendBuffer.array,
                        sendBuffer.arrayOffset + byteBuffer.position(),
                        frameSize);

                if (bytesReady == frameSize)
                {
                    send(byteBuffer, position, messageSize, ptt);
                    frames++;
                }
                else
                {
                    Log.e(LOG_TAG, "readSize=" + frameSize + " bytesReady=" + bytesReady);
                    break;
                }
            }
        }
        catch (final InterruptedException ex)
        {
            Log.e(LOG_TAG, ex.toString(), ex);
            interrupted = true;
        }

        m_audioRecord.release();
        sendBuffer.release();

        Log.i( LOG_TAG, "run [" + m_audioFormat + "]: done" );

        if (interrupted)
            Thread.currentThread().interrupt();
    }

    void setPTT(boolean ptt)
    {
        m_lock.lock();
        try
        {
            if (BuildConfig.DEBUG && (m_state != START) && (m_state != RUN))
                throw new AssertionError();
            m_ptt = ptt;
        }
        finally
        {
            m_lock.unlock();
        }
    }

    void startRecording(boolean ptt)
    {
        Log.d(LOG_TAG, "startRecording");
        m_lock.lock();
        try
        {
            if (m_state == IDLE)
            {
                m_state = START;
                m_cond.signal();
            }
            else if (m_state == STOP)
                m_state = RUN;
            m_ptt = ptt;
        }
        finally
        {
            m_lock.unlock();
        }
    }

    void stopRecording()
    {
        Log.d(LOG_TAG, "stopRecording");
        m_lock.lock();
        try
        {
            if (m_state != IDLE)
                m_state = STOP;
        }
        finally
        {
            m_lock.unlock();
        }
    }

    static ByteBuffer loadWavResource(Resources resources, int resourceId)
    {
        try
        {
            final InputStream inputStream = resources.openRawResource(resourceId);
            try
            {
                final ByteBuffer buffer = WAV.loadData(inputStream);

                // Buffer returned by WAV.loadData() contains the whole resource (WAV file),
                // but the current position is start of PCM data.
                // We could use space before the data for the message header.
                final int messageHeaderSize = Protocol.AudioFrame.getMessageSize(0);
                int position = buffer.position();
                if (position < messageHeaderSize)
                {
                    Log.w(LOG_TAG, "Not enough space for the message header in the buffer");
                    return null;
                }

                Log.i(LOG_TAG, "Using data from the resource " + resourceId + " of size " + buffer.remaining() + " as roger beep");

                final int frameSize = buffer.remaining();
                position -= messageHeaderSize;
                buffer.position(position);
                buffer.order(Protocol.BYTE_ORDER);
                Protocol.AudioFrame.init(buffer, /*start of batch*/false, frameSize);
                buffer.position(position);

                return buffer;
            }
            catch (final WAV.ReadException ex)
            {
                Log.e(LOG_TAG, "WAV.loadData() for resource " + resourceId + " failed: " + ex);
            }
            finally
            {
                inputStream.close();
            }
        }
        catch (final Resources.NotFoundException ex)
        {
            Log.e(LOG_TAG, "openRawResource(" + resourceId + ") failed: " + ex);
        }
        catch (final IOException ex)
        {
            Log.e(LOG_TAG, "Failed to read resource " + resourceId + ": " + ex);
        }
        return null;
    }

    ByteBuffer loadRogerBeep(Resources resources)
    {
        final int sampleRate = m_audioRecord.getSampleRate();
        int resourceId = -1;
        for (SampleRateInfo sri : s_sampleRates)
        {
            if (sri.sampleRate == sampleRate)
            {
                resourceId = sri.rogerBeepResourceId;
                break;
            }
        }

        if (resourceId == -1)
            return null;
        else
            return loadWavResource(resources, resourceId);
    }

    void setRogerBeepOn(Resources resources)
    {
        if (m_rogerBeep == null)
            m_rogerBeep = loadRogerBeep(resources);
    }

    void setRogerBeepOff()
    {
        m_rogerBeep = null;
    }

    void shutdown()
    {
        Log.d(LOG_TAG, "shutdown");
        m_lock.lock();
        try
        {
            if (m_state == IDLE)
                m_cond.signal();
            m_state = SHTDN;
        }
        finally
        {
            m_lock.unlock();
        }

        boolean interrupted = false;
        try
        {
            m_thread.join();
        }
        catch (final InterruptedException ex)
        {
            interrupted = true;
            Log.e(LOG_TAG, ex.toString(), ex);
        }
        if (interrupted)
            Thread.currentThread().interrupt();
    }

    static AudioRecorder create(SessionManager sessionManager)
    {
        for (SampleRateInfo sampleRateInfo : s_sampleRates)
        {
            final int sampleRate = sampleRateInfo.sampleRate;
            final int channelConfig = AudioFormat.CHANNEL_IN_MONO;
            final int minBufferSize = AudioRecord.getMinBufferSize(
                    sampleRate, channelConfig, AudioFormat.ENCODING_PCM_16BIT);

            if ((minBufferSize != AudioRecord.ERROR) &&
                (minBufferSize != AudioRecord.ERROR_BAD_VALUE))
            {
                /* Let's read not more than 1/5 sec to reduce latency. */
                final int frameSize = (sampleRate * (Short.SIZE / Byte.SIZE) / 5) & (Integer.MAX_VALUE - 1);
                int bufferSize = (frameSize * 4);
                if (bufferSize < minBufferSize)
                    bufferSize = minBufferSize;

                final AudioRecord audioRecord = new AudioRecord(
                        MediaRecorder.AudioSource.MIC,
                        sampleRate,
                        channelConfig,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferSize);

                final String audioFormat = ("PCM:" + sampleRate);
                return new AudioRecorder(sessionManager, audioRecord, audioFormat, frameSize);
            }
        }
        return null;
    }
}
