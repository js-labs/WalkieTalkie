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

import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Looper;
import android.util.Log;
import android.util.Base64;
import org.jsl.collider.TimerQueue;
import org.jsl.collider.Connector;
import org.jsl.collider.Acceptor;
import org.jsl.collider.Collider;
import org.jsl.collider.Session;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.locks.ReentrantLock;

public class Channel
{
    private static final String LOG_TAG = "Channel";

    private static class ServiceInfo
    {
        public int ver;
        public NsdServiceInfo nsdServiceInfo;
        public ResolveListener resolveListener;
        public Connector connector;
        public Session session;
    }

    private final String m_audioFormat;
    private final MainActivity m_activity;
    private final Collider m_collider;
    private final NsdManager m_nsdManager;
    private final String m_serviceType;
    private final String m_name;
    private final TimerQueue m_timerQueue;
    private final int m_pingInterval;

    private final ReentrantLock m_lock;
    private final TreeMap<String, ServiceInfo> m_serviceInfo;
    private final HashSet<Session> m_sessions;
    private ChannelAcceptor m_acceptor;
    private RegistrationListener m_registrationListener;
    private String m_serviceName;
    private boolean m_stop;
    private Phaser m_phaser;

    private class RegistrationListener implements NsdManager.RegistrationListener
    {
        public void onRegistrationFailed( NsdServiceInfo serviceInfo, int errorCode )
        {
            Log.e( LOG_TAG, m_name + ": onRegistrationFailed: " + serviceInfo + " (" + errorCode + ")" );
            Acceptor acceptor;
            m_lock.lock();
            try
            {
                if (BuildConfig.DEBUG && ((m_registrationListener != this) || (m_acceptor != null)))
                    throw new AssertionError();

                m_registrationListener = null;
                acceptor = m_acceptor;
            }
            finally
            {
                m_lock.unlock();
            }

            try
            {
                m_collider.removeAcceptor( acceptor );
            }
            catch (final InterruptedException ex)
            {
                Log.w( LOG_TAG, ex.toString() );
            }

            m_lock.lock();
            try
            {
                m_acceptor = null;
                if (m_stop)
                {
                    m_stop = false;
                    if (m_phaser != null)
                    {
                        final int phase = m_phaser.arriveAndDeregister();
                        if (BuildConfig.DEBUG && (phase != 0))
                            throw new AssertionError();
                        m_phaser = null;
                    }
                }
            }
            finally
            {
                m_lock.unlock();
            }
        }

        public void onUnregistrationFailed( NsdServiceInfo serviceInfo, int errorCode )
        {
            /* Should not be called */
            Log.e( LOG_TAG, m_name + ": onUnregistrationFailed: " + serviceInfo + " (" + errorCode + ")" );
            if (BuildConfig.DEBUG)
                throw new AssertionError();
        }

        public void onServiceRegistered( NsdServiceInfo nsdServiceInfo )
        {
            /* Service is registered now,
             * we use service name to distinguish client from server,
             * so now we can try to connect to the services already discovered.
             */
            Log.i( LOG_TAG, m_name + ": onServiceRegistered: " + nsdServiceInfo );
            Acceptor acceptor;
            m_lock.lock();
            try
            {
                if (!m_stop)
                {
                    /* Now we have to connect to already discovered services. */
                    m_registrationListener = this;
                    m_serviceName = nsdServiceInfo.getServiceName();
                    for (TreeMap.Entry<String, ServiceInfo> entry : m_serviceInfo.entrySet())
                    {
                        if (m_serviceName.compareTo(entry.getKey()) > 0)
                        {
                            Log.i( LOG_TAG, m_name + ": connect to " + entry.getKey() );

                            final ServiceInfo serviceInfo = entry.getValue();
                            if ((serviceInfo.resolveListener == null) &&
                                (serviceInfo.connector == null) &&
                                (serviceInfo.session == null))
                            {
                                serviceInfo.resolveListener = new ResolveListener();
                                m_nsdManager.resolveService( serviceInfo.nsdServiceInfo, serviceInfo.resolveListener );
                            }
                            else
                            {
                                Log.i( LOG_TAG, m_name + ": service " + entry.getKey() +
                                        " is being connected: " + serviceInfo);
                            }
                        }
                        else
                            Log.d( LOG_TAG, m_name + ": skip service " + entry.getKey() );
                    }

                    final String [] ss = nsdServiceInfo.getServiceName().split( MainActivity.SERVICE_NAME_SEPARATOR );
                    final String channelName = new String( Base64.decode(ss[0], 0) );
                    m_activity.onServiceRegistered( channelName );
                    return;
                }
                acceptor = m_acceptor;
            }
            finally
            {
                m_lock.unlock();
            }

            /* Appear here only if channel is being stopped. */

            try
            {
                m_collider.removeAcceptor( acceptor );
            }
            catch (final InterruptedException ex)
            {
                Log.w( LOG_TAG, m_name + ": " + ex.toString() );
            }

            m_lock.lock();
            try
            {
                m_acceptor = null;
                m_stop = false;
                if (m_phaser != null)
                {
                    final int phase = m_phaser.arriveAndDeregister();
                    if (BuildConfig.DEBUG && (phase != 0))
                        throw new AssertionError();
                    m_phaser = null;
                }
            }
            finally
            {
                m_lock.unlock();
            }
        }

        public void onServiceUnregistered( NsdServiceInfo nsdServiceInfo )
        {
            Log.i( LOG_TAG, m_name + ": onServiceUnregistered: " + nsdServiceInfo );
            Acceptor acceptor;
            m_lock.lock();
            try
            {
                if (BuildConfig.DEBUG && (!m_stop || (m_acceptor == null)))
                    throw new AssertionError();

                m_registrationListener = null;
                acceptor = m_acceptor;
            }
            finally
            {
                m_lock.unlock();
            }

            /* Would be better to remove acceptor not under the lock. */
            try
            {
                m_collider.removeAcceptor( acceptor );
            }
            catch (final InterruptedException ex)
            {
                Log.w( LOG_TAG, m_name + ": " + ex.toString() );
            }

            m_lock.lock();
            try
            {
                if (BuildConfig.DEBUG && (m_registrationListener != null))
                    throw new AssertionError();

                if (BuildConfig.DEBUG && (m_acceptor != acceptor))
                    throw new AssertionError();

                m_acceptor = null;

                if (noPendingResolvesAndPendingConnectorsAndSessionsLocked())
                {
                    /* Channel is being stopped on activity pause. */
                    m_stop = false;
                    final int phase = m_phaser.arriveAndDeregister();
                    if (BuildConfig.DEBUG && (phase != 0))
                        throw new AssertionError();
                    m_phaser = null;
                }
            }
            finally
            {
                m_lock.unlock();
            }
        }
    }

    private boolean noPendingResolvesAndPendingConnectorsAndSessionsLocked()
    {
        /* Lock is supposed to be locked. */
        if (BuildConfig.DEBUG && !m_lock.isHeldByCurrentThread())
            throw new AssertionError();

        for (Map.Entry<String, ServiceInfo> entry : m_serviceInfo.entrySet())
        {
            final ServiceInfo serviceInfo = entry.getValue();
            if (serviceInfo.resolveListener != null)
            {
                if (BuildConfig.DEBUG &&
                    ((serviceInfo.connector != null) ||
                     (serviceInfo.session != null)))
                {
                    throw new AssertionError();
                }
                return false;
            }
            else if (serviceInfo.connector != null)
            {
                if (BuildConfig.DEBUG && (serviceInfo.session != null))
                    throw new AssertionError();
                return false;
            }
            else if (serviceInfo.session != null)
                return false;
        }

        return (m_sessions.size() == 0);
    }

    private class ResolveListener implements NsdManager.ResolveListener
    {
        public void onResolveFailed( NsdServiceInfo nsdServiceInfo, int errorCode )
        {
            Log.i( LOG_TAG, m_name + ": onResolveFailed: " + nsdServiceInfo );
            m_lock.lock();
            try
            {
                final ServiceInfo serviceInfo = m_serviceInfo.get( nsdServiceInfo.getServiceName() );
                if (serviceInfo != null)
                {
                    if (BuildConfig.DEBUG &&
                        ((serviceInfo.resolveListener != this) ||
                         (serviceInfo.connector != null) ||
                         (serviceInfo.session != null)))
                    {
                        throw new AssertionError();
                    }

                    if (m_stop)
                    {
                        serviceInfo.resolveListener = null;

                        if ((m_acceptor == null) &&
                            noPendingResolvesAndPendingConnectorsAndSessionsLocked())
                        {
                            m_stop = false;
                            if (m_phaser != null)
                            {
                                final int phase = m_phaser.arriveAndDeregister();
                                if (BuildConfig.DEBUG && (phase != 0))
                                    throw new AssertionError();
                                m_phaser = null;
                            }
                        }
                    }
                    else if (serviceInfo.ver > 0)
                    {
                        /* NSD service info was updated while we tried to resolve one,
                         * let's try to resolve a new one.
                         */
                        serviceInfo.ver = 0;
                        m_nsdManager.resolveService( serviceInfo.nsdServiceInfo, this );
                    }
                    else
                        serviceInfo.resolveListener = null;
                }
            }
            finally
            {
                m_lock.unlock();
            }
        }

        public void onServiceResolved( NsdServiceInfo nsdServiceInfo )
        {
            Log.i( LOG_TAG, m_name + ": onServiceResolved: " + nsdServiceInfo );
            m_lock.lock();
            try
            {
                final String serviceName = nsdServiceInfo.getServiceName();
                final ServiceInfo serviceInfo = m_serviceInfo.get( serviceName );
                if (serviceInfo != null)
                {
                    if (BuildConfig.DEBUG &&
                        ((serviceInfo.resolveListener != this) ||
                         (serviceInfo.connector != null) ||
                         (serviceInfo.session != null)))
                    {
                        throw new AssertionError();
                    }

                    serviceInfo.resolveListener = null;

                    if (m_stop)
                    {
                        if ((m_acceptor == null) &&
                            noPendingResolvesAndPendingConnectorsAndSessionsLocked())
                        {
                            m_stop = false;
                            if (m_phaser != null)
                            {
                                final int phase = m_phaser.arriveAndDeregister();
                                if (BuildConfig.DEBUG && (phase != 0))
                                    throw new AssertionError();
                                m_phaser = null;
                            }
                        }
                    }
                    else
                    {
                        final InetSocketAddress addr = new InetSocketAddress( nsdServiceInfo.getHost(), nsdServiceInfo.getPort() );
                        serviceInfo.connector = new ChannelConnector( addr, serviceName );
                        m_collider.addConnector( serviceInfo.connector );
                    }
                }
            }
            finally
            {
                m_lock.unlock();
            }
        }
    }

    private class ChannelAcceptor extends Acceptor
    {
        public Session.Listener createSessionListener( Session session )
        {
            Log.i( LOG_TAG, m_name + ": session accepted: " + session.getRemoteAddress() );
            m_lock.lock();
            try
            {
                if (!m_stop)
                {
                    if (m_sessions.add(session))
                    {
                        return new HandshakeServerSession(
                                m_audioFormat, Channel.this, session, m_timerQueue, m_pingInterval );
                    }
                    else
                    {
                        /* should not happen */
                        if (BuildConfig.DEBUG)
                            throw new AssertionError();
                    }
                }
                /* else channel is being stopped, just skip a new income connection. */
            }
            finally
            {
                m_lock.unlock();
            }
            return null;
        }

        public void onAcceptorStarted( Collider collider, int localPort )
        {
            Log.i( LOG_TAG, m_name + ": acceptor started: " + localPort );

            /* In a case if service with the same name already registered
             * NSD (Bonjour) adds a "(<number>)" to the new service's name.
             * Let's add some marker (MainActivity.SERVICE_NAME_SEPARATOR)
             * to be used as an end of the service name later when will be resolved.
             */
            m_lock.lock();
            try
            {
                if (!m_stop)
                {
                    final NsdServiceInfo serviceInfo = new NsdServiceInfo();
                    final int coderFlags = (Base64.NO_PADDING | Base64.NO_WRAP);
                    final String serviceName = Base64.encodeToString(m_name.getBytes(), coderFlags) + MainActivity.SERVICE_NAME_SEPARATOR;
                    serviceInfo.setServiceType( m_serviceType );
                    serviceInfo.setServiceName( serviceName );
                    serviceInfo.setPort( localPort );

                    Log.i( LOG_TAG, "register service: " + serviceInfo );
                    final RegistrationListener registrationListener = new RegistrationListener();
                    m_nsdManager.registerService( serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener );
                    return;
                }
            }
            finally
            {
                m_lock.unlock();
            }

            /* Appear here only if channel is being sopped. */

            try
            {
                m_collider.removeAcceptor( this );
            }
            catch (final InterruptedException ex)
            {
                Log.w( LOG_TAG, ex.toString() );
            }

            m_lock.lock();
            try
            {
                m_stop = false;
                if (m_phaser != null)
                {
                    final int phase = m_phaser.arriveAndDeregister();
                    if (BuildConfig.DEBUG && (phase != 0))
                        throw new AssertionError();
                    m_phaser = null;
                }
            }
            finally
            {
                m_lock.unlock();
            }
        }
    }

    private class ChannelConnector extends Connector
    {
        private final String m_serviceName;

        public ChannelConnector( InetSocketAddress addr, String serviceName )
        {
            super( addr );
            m_serviceName = serviceName;
        }

        public Session.Listener createSessionListener( Session session )
        {
            Log.i( LOG_TAG, m_name + ": connected to [" + m_serviceName + "] " + getAddr() );
            m_lock.lock();
            try
            {
                final ServiceInfo serviceInfo = m_serviceInfo.get( m_serviceName );
                if (serviceInfo == null)
                {
                    /* strange, should not happen */
                    if (BuildConfig.DEBUG)
                        throw new AssertionError();
                }
                else
                {
                    if (BuildConfig.DEBUG &&
                        ((serviceInfo.resolveListener != null) ||
                         (serviceInfo.connector != this) ||
                         (serviceInfo.session != null)))
                    {
                        throw new AssertionError();
                    }

                    serviceInfo.connector = null;

                    if (m_stop)
                    {
                        session.closeConnection();
                        if ((m_acceptor == null) &&
                            noPendingResolvesAndPendingConnectorsAndSessionsLocked())
                        {
                            m_stop = false;
                            if (m_phaser != null)
                            {
                                final int phase = m_phaser.arriveAndDeregister();
                                if (BuildConfig.DEBUG && (phase != 0))
                                    throw new AssertionError();
                                m_phaser = null;
                            }
                        }
                    }
                    else
                    {
                        serviceInfo.session = session;
                        return new HandshakeClientSession(
                                Channel.this, m_audioFormat, m_serviceName, session, m_timerQueue, m_pingInterval );
                    }
                }
            }
            finally
            {
                m_lock.unlock();
            }
            return null;
        }

        public void onException( IOException ex )
        {
            Log.i( LOG_TAG, m_name + ": exception [" + m_serviceName + "] " + getAddr() + ": " + ex.toString() );
            m_lock.lock();
            try
            {
                final ServiceInfo serviceInfo = m_serviceInfo.get( m_serviceName );
                if (serviceInfo == null)
                {
                    /* strange, should not happen */
                    if (BuildConfig.DEBUG)
                        throw new AssertionError();
                }
                else
                {
                    if (BuildConfig.DEBUG &&
                        ((serviceInfo.resolveListener != null) ||
                         (serviceInfo.connector != this) ||
                         (serviceInfo.session != null)))
                    {
                        throw new AssertionError();
                    }

                    serviceInfo.connector = null;

                    if (m_stop)
                    {
                        if ((m_acceptor == null) &&
                            noPendingResolvesAndPendingConnectorsAndSessionsLocked())
                        {
                            m_stop = false;
                            if (m_phaser != null)
                            {
                                final int phase = m_phaser.arriveAndDeregister();
                                if (BuildConfig.DEBUG && (phase != 0))
                                    throw new AssertionError();
                                m_phaser = null;
                            }
                        }
                    }
                    else if (serviceInfo.ver > 0)
                    {
                        /* NSD service info was updated while we tried to resolve one,
                         * let's try to resolve a new one.
                         */
                        serviceInfo.ver = 0;
                        serviceInfo.resolveListener = new ResolveListener();
                        m_nsdManager.resolveService( serviceInfo.nsdServiceInfo, serviceInfo.resolveListener );
                    }
                }
            }
            finally
            {
                m_lock.unlock();
            }
        }
    }

    public Channel(
            String audioFormat,
            MainActivity activity,
            Collider collider,
            NsdManager nsdManager,
            String serviceType,
            String name,
            TimerQueue timerQueue,
            int pingInterval )
    {
        m_audioFormat = audioFormat;
        m_activity = activity;
        m_collider = collider;
        m_nsdManager = nsdManager;
        m_serviceType = serviceType;
        m_name = name;
        m_timerQueue = timerQueue;
        m_pingInterval = pingInterval;
        m_serviceInfo = new TreeMap<String, ServiceInfo>();
        m_sessions = new HashSet<Session>();
        m_lock = new ReentrantLock();

        m_acceptor = new ChannelAcceptor();
        try
        {
            m_collider.addAcceptor( m_acceptor );
        }
        catch (final IOException ex)
        {
            Log.d( LOG_TAG, ex.toString() );
            m_acceptor = null;
        }
    }

    public void addService( NsdServiceInfo nsdServiceInfo )
    {
        /* Run in the NSD manager thread */
        final String serviceName = nsdServiceInfo.getServiceName();

        m_lock.lock();
        try
        {
            if (BuildConfig.DEBUG && m_stop)
                throw new AssertionError();

            ServiceInfo serviceInfo = m_serviceInfo.get( serviceName );
            if (serviceInfo == null)
            {
                serviceInfo = new ServiceInfo();
                m_serviceInfo.put( serviceName, serviceInfo );
            }
            /* Local services will appear one time for each network interface.
             * else
             * {
             *    if (BuildConfig.DEBUG && (serviceInfo.nsdServiceInfo != null))
             *       throw new AssertionError( "Service already registered: " + nsdServiceInfo );
             * }
             */

            serviceInfo.nsdServiceInfo = nsdServiceInfo;

            if ((m_serviceName != null) &&
                (m_serviceName.compareTo(serviceName) > 0))
            {
                if ((serviceInfo.session == null) &&
                    (serviceInfo.connector == null))
                {
                    if (serviceInfo.resolveListener == null)
                    {
                        serviceInfo.ver = 0;
                        serviceInfo.resolveListener = new ResolveListener();
                        m_nsdManager.resolveService( nsdServiceInfo, serviceInfo.resolveListener );
                    }
                    else
                    {
                        /* Service is being resolved right now,
                         * will try to resolve new service when current resolve
                         * operation will finish or fail.
                         */
                        serviceInfo.ver++;
                    }
                }
            }
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public void removeSession( String serviceName, Session session )
    {
        m_lock.lock();
        try
        {
            if (serviceName == null)
            {
                /* Session created with acceptor */
                if (!m_sessions.remove(session))
                {
                    /* strange, should not happen */
                    if (BuildConfig.DEBUG)
                        throw new AssertionError();

                    Log.w( LOG_TAG, m_name + ": session " + session + " not registered (" +
                        session.getRemoteAddress() + ")." );

                    /* Channel state not changed, return now. */
                    return;
                }
            }
            else
            {
                final ServiceInfo serviceInfo = m_serviceInfo.get( serviceName );
                if (serviceInfo == null)
                {
                    /* strange, should not happen... */
                    if (BuildConfig.DEBUG)
                        throw new AssertionError();
                }
                else
                {
                    if (BuildConfig.DEBUG && (serviceInfo.session != session))
                        throw new AssertionError();
                    serviceInfo.session = null;
                }
            }

            if (m_stop)
            {
                if ((m_acceptor == null) &&
                     noPendingResolvesAndPendingConnectorsAndSessionsLocked())
                {
                    m_stop = false;
                    if (m_phaser != null)
                    {
                        final int phase = m_phaser.arriveAndDeregister();
                        if (BuildConfig.DEBUG && (phase != 0))
                            throw new AssertionError();
                        m_phaser = null;
                    }
                }
            }
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public boolean removeService( NsdServiceInfo nsdServiceInfo )
    {
        if (BuildConfig.DEBUG && (Looper.getMainLooper().getThread() != Thread.currentThread()))
            throw new AssertionError();

        final String serviceName = nsdServiceInfo.getServiceName();

        m_lock.lock();
        try
        {
            final ServiceInfo serviceInfo = m_serviceInfo.get( serviceName );
            if (serviceInfo == null)
            {
                /* Should not happen,
                 * but probably would be better to handle a case.
                 */
                Log.w( LOG_TAG, "Internal error: service was not registered: " + nsdServiceInfo );
                return false;
            }

            if ((serviceInfo.resolveListener == null) &&
                (serviceInfo.connector == null) &&
                (serviceInfo.session == null))
            {
                m_serviceInfo.remove( serviceName );
                return (m_serviceInfo.isEmpty() && (m_acceptor == null));
            }

            /* There is still some activity with this service,
             * let's keep it while all tasks will not be done.
             */
            serviceInfo.nsdServiceInfo = null;
        }
        finally
        {
            m_lock.unlock();
        }

        return false;
    }

    public boolean isConnected()
    {
        return (m_acceptor != null);
    }

    public void stop( Phaser phaser )
    {
        if (BuildConfig.DEBUG && (Looper.getMainLooper().getThread() != Thread.currentThread()))
            throw new AssertionError();

        m_lock.lock();
        try
        {
            if (m_stop)
            {
                /* Channel is being stopped. */
                if (BuildConfig.DEBUG && (m_phaser != null))
                    throw new AssertionError();
                m_phaser = phaser;
            }
            else
            {
                if (m_acceptor != null)
                {
                    m_stop = true;
                    m_phaser = phaser;
                    m_phaser.register();

                    if (m_registrationListener != null)
                    {
                        m_nsdManager.unregisterService( m_registrationListener );

                        for (TreeMap.Entry<String, ServiceInfo> entry : m_serviceInfo.entrySet())
                        {
                            final Session session = entry.getValue().session;
                            if (session != null)
                                session.closeConnection();
                        }

                        for (Session session : m_sessions)
                            session.closeConnection();
                    }
                }
            }
        }
        finally
        {
            m_lock.unlock();
        }
    }

    public String toString()
    {
        /* The string will be actually displayed in the list view */
        return m_name + " [" + m_serviceInfo.size() + "]";
    }

    public final String getName()
    {
        return m_name;
    }
}
