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

import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

public class Phaser
{
    private final ReentrantLock m_lock;
    private final Condition m_cond;
    private int m_phase;
    private int m_parties;
    private int m_unarrived;
    private int m_waiters;

    public Phaser()
    {
        m_lock = new ReentrantLock();
        m_cond = m_lock.newCondition();
        m_phase = 0;
        m_parties = 0;
        m_unarrived = 0;
        m_waiters = 0;
    }

    public int arriveAndDeregister()
    {
        int phase;
        m_lock.lock();
        try
        {
            if (BuildConfig.DEBUG && (m_unarrived == 0))
                throw new IllegalStateException();

            if (BuildConfig.DEBUG && (m_parties == 0))
                throw new IllegalStateException();

            m_parties--;

            if (--m_unarrived == 0)
            {
                phase = m_phase;
                m_phase++;
                if (m_waiters > 0)
                    m_cond.signalAll();
            }
            else
                phase = m_phase;
        }
        finally
        {
            m_lock.unlock();
        }
        return phase;
    }

    public int arrive()
    {
        int phase;
        m_lock.lock();
        try
        {
            if (BuildConfig.DEBUG && (m_unarrived == 0))
                throw new IllegalStateException();

            if (--m_unarrived == 0)
            {
                phase = m_phase;
                m_phase++;
                if (m_waiters > 0)
                    m_cond.signalAll();
            }
            else
                phase = m_phase;
        }
        finally
        {
            m_lock.unlock();
        }
        return phase;
    }

    public int awaitAdvance( int phase )
    {
        m_lock.lock();
        try
        {
            while ((m_phase <= phase) && (m_unarrived > 0))
            {
                m_waiters++;
                try
                {
                    m_cond.await();
                }
                catch (InterruptedException ex)
                {
                    Thread.currentThread().interrupt();
                }
                m_waiters--;
            }
            phase = m_phase;
        }
        finally
        {
            m_lock.unlock();
        }
        return phase;
    }

    public int register()
    {
        int phase;
        m_lock.lock();
        try
        {
            m_parties++;
            m_unarrived++;
            phase = m_phase;
        }
        finally
        {
            m_lock.unlock();
        }
        return phase;
    }
}
