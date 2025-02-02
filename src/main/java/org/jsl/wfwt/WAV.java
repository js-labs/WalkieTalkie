/*
 * Copyright (C) 2025 Sergey Zubarev, info@js-labs.org
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

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

class WAV
{
    static class ReadException extends Exception
    {
        final String description;

        ReadException(String description)
        {
            this.description = description;
        }
    }

    private static String intToString(int v)
    {
        final StringBuilder sb = new StringBuilder();
        v = Integer.reverseBytes(v);
        for (int idx=0; idx<4; idx++, v >>= 8)
        {
            final int ch = (v & 0xFF);
            if ((ch >= 32) && (ch <= 127))
                sb.append((char) ch);
            else
            {
                sb.append("\\x");
                sb.append(Integer.toHexString(ch));
            }
        }
        return sb.toString();
    }

    private static class RIFFHeader
    {
        static final int SIZE = 12;

        private static void read(ByteBuffer buffer) throws ReadException
        {
            final int remaining = buffer.remaining();
            if (remaining < SIZE)
                throw new ReadException("less than " + SIZE + " bytes");
            final int chunkId = buffer.getInt();
            if (chunkId != 0x46464952)
                throw new ReadException("expected 'RIFF' in the begin");
            final int chunkSize = buffer.getInt();
            if (remaining != (8 + chunkSize))
                throw new ReadException("unexpected chunk size " + chunkSize + " instead of " + (remaining - 8));
        }
    }

    private static class FMTSubChunk
    {
        static final int SIZE = 16;

        final short audioFormat;
        final short numChannels;
        final int sampleRate;
        final int byteRate;
        final short blockAlign;
        final short bitsPerSample;

        FMTSubChunk(short audioFormat, short numChannels, int sampleRate, int byteRate, short blockAlign, short bitsPerSample)
        {
            this.audioFormat = audioFormat;
            this.numChannels = numChannels;
            this.sampleRate = sampleRate;
            this.byteRate = byteRate;
            this.blockAlign = blockAlign;
            this.bitsPerSample = bitsPerSample;
        }

        static FMTSubChunk read(ByteBuffer buffer) throws ReadException
        {
            final int remaining = buffer.remaining();
            final int totalHeaderSize = ((Integer.SIZE / Byte.SIZE) + SIZE);
            if (remaining < totalHeaderSize)
                throw new ReadException("less than " + totalHeaderSize + " bytes");
            final int subChunkId = buffer.getInt();
            if (subChunkId != 0x20746D66)
                throw new ReadException("expected 'fmt ' marker in the beginning of the subchunk");
            final int subChunkSize = buffer.getInt();
            if (subChunkSize != SIZE)
                throw new ReadException("unexpected subchunk size " + subChunkSize + " instead of " + SIZE);
            final short audioFormat = buffer.getShort();
            final short numChannels = buffer.getShort();
            final int sampleRate = buffer.getInt();
            final int byteRate = buffer.getInt();
            final short blockAlign = buffer.getShort();
            final short bitsPerSample = buffer.getShort();
            return new FMTSubChunk(audioFormat, numChannels, sampleRate, byteRate, blockAlign, bitsPerSample);
        }
    }

    private static class DataSubChunk {
        final int size;

        DataSubChunk(int size)
        {
            this.size = size;
        }

        static DataSubChunk read(ByteBuffer buffer) throws ReadException
        {
            final int remaining = buffer.remaining();
            final int headerSize = ((Integer.SIZE * 2) / Byte.SIZE);
            if (remaining < headerSize)
                throw new ReadException("less than " + headerSize + " bytes");
            final int subChunkId = buffer.getInt();
            if (subChunkId != 0x61746164)
                throw new ReadException("expected 'data' marker in the beginning of the subchunk");
            final int size = buffer.getInt();
            return new DataSubChunk(size);
        }
    }

    static ByteBuffer loadData(InputStream inputStream) throws IOException, ReadException
    {
        final int available = inputStream.available();
        final ByteBuffer buffer = ByteBuffer.allocate(available);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        int rc = inputStream.read(buffer.array(), 0, available);
        if (rc != available)
            throw new ReadException("Failed to read whole data of " + available + " bytes");

        try
        {
            RIFFHeader.read(buffer);
        }
        catch (final ReadException ex)
        {
            throw new IOException("Invalid RIFF header: " + ex.description);
        }

        final int format = buffer.getInt();
        if (format != 0x45564157)
            throw new ReadException("unexpected format " + intToString(format) + " instead of 'WAVE'");

        final FMTSubChunk fmtSubChunk;
        try
        {
            fmtSubChunk = FMTSubChunk.read(buffer);
            if (fmtSubChunk.audioFormat != 1)
                throw new ReadException("Unsupported audio format " + fmtSubChunk.audioFormat);
            if (fmtSubChunk.numChannels != 1)
                throw new ReadException("Unsupported number of channels " + fmtSubChunk.numChannels);
        }
        catch (final ReadException ex)
        {
            throw new ReadException("Invalid fmt subchunk header: " + ex.description);
        }

        final DataSubChunk dataSubChunk;
        try
        {
            dataSubChunk = DataSubChunk.read(buffer);
        }
        catch (final ReadException ex)
        {
            throw new ReadException("Invalid data subchunk header: " + ex.description);
        }

        final int remaining = buffer.remaining();
        if (remaining < dataSubChunk.size)
            throw new ReadException("Invalid input stream, available bytes is less than " + dataSubChunk.size);

        return buffer;
    }
}
