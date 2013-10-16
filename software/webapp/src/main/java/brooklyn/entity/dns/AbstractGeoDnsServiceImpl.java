package brooklyn.entity.dns;

import static com.google.common.base.Preconditions.checkNotNull;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import brooklyn.entity.Entity;
import brooklyn.entity.Group;
import brooklyn.entity.basic.AbstractEntity;
import brooklyn.entity.basic.Attributes;
import brooklyn.entity.basic.DynamicGroup;
import brooklyn.entity.basic.Lifecycle;
import brooklyn.entity.webapp.WebAppService;
import brooklyn.location.geo.HostGeoInfo;
import brooklyn.util.collections.MutableSet;
import brooklyn.util.exceptions.Exceptions;
import brooklyn.util.flags.SetFromFlag;
import brooklyn.util.net.Networking;
import brooklyn.util.time.Duration;
import brooklyn.util.time.Time;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

public abstract class AbstractGeoDnsServiceImpl extends AbstractEntity implements AbstractGeoDnsService {
    private static final Logger log = LoggerFactory.getLogger(AbstractGeoDnsService.class);

    @SetFromFlag
    protected Entity targetEntityProvider;

    protected Map<Entity, HostGeoInfo> targetHosts = Collections.synchronizedMap(new LinkedHashMap<Entity, HostGeoInfo>());
    
    // We complain (at debug) when we encounter a target entity for whom we can't derive hostname/ip information; 
    // this is the commonest case for the transient condition between the time the entity is created and the time 
    // it is started (at which point the location is specified). This set contains those entities we've complained 
    // about already, to avoid repetitive logging.
    transient protected Set<Entity> entitiesWithoutHostname = new HashSet<Entity>();

    // We complain (at info/warn) when we encounter a target entity for whom we can't derive geo information, even 
    // when hostname/ip is known. This set contains those entities we've complained about already, to avoid repetitive 
    // logging.
    transient protected Set<Entity> entitiesWithoutGeoInfo = new HashSet<Entity>();

    public AbstractGeoDnsServiceImpl() {
        super();
    }
    
    @Override
    public Map<Entity, HostGeoInfo> getTargetHosts() {
        return targetHosts;
    }
    
    @Override
    public void onManagementBecomingMaster() {
        super.onManagementBecomingMaster();
        beginPoll();
    }
    @Override
    public void onManagementNoLongerMaster() {
        endPoll();
        super.onManagementNoLongerMaster();
    }

    @Override
    public void destroy() {
        setServiceState(Lifecycle.DESTROYED);
        super.destroy();
    }
        
    @Override
    public void setServiceState(Lifecycle state) {
        setAttribute(HOSTNAME, getHostname());
        setAttribute(SERVICE_STATE, state);
        setAttribute(SERVICE_UP, state==Lifecycle.RUNNING);
    }
    
    @Override
    public void setTargetEntityProvider(final Entity entityProvider) {
        this.targetEntityProvider = checkNotNull(entityProvider, "targetEntityProvider");
    }
    
    // TODO: remove polling once locations can be determined via subscriptions
    ScheduledFuture poll;
    protected void beginPoll() {
        if (log.isDebugEnabled()) log.debug("GeoDns {} starting poll", this);
        if (poll!=null) {
            log.warn("GeoDns duplicate call to beginPoll, ignoring");
            return;
        }
        if (targetEntityProvider==null) {
            log.warn("GeoDns {} has no targetEntityProvider; polling will have no-effect until it is set", this);
        }
        
        // TODO Should re-use the execution manager's thread pool, somehow
        ThreadFactory threadFactory = new ThreadFactoryBuilder()
                .setNameFormat("brooklyn-geodnsservice-%d")
                .build();
        poll = Executors.newSingleThreadScheduledExecutor(threadFactory).scheduleAtFixedRate(
            new Runnable() {
                public void run() {
                    try {
                        refreshGroupMembership();
                    } catch (Throwable t) {
                        log.warn("Error refreshing group membership", t);
                        Exceptions.propagate(t);
                    }
                }
            }, 0, getConfig(POLL_PERIOD), TimeUnit.MILLISECONDS
        );
    }
    
    protected void endPoll() {
        if (poll!=null) {
            if (log.isDebugEnabled()) log.debug("GeoDns {} ending poll", this);
            poll.cancel(true);
            poll = null;
        }
    }

    /** should set up so these hosts are targeted, and setServiceState appropriately */
    protected abstract void reconfigureService(Collection<HostGeoInfo> targetHosts);
    
    @Override
    public abstract String getHostname();
    
    long lastUpdate = -1;
    
    // TODO: remove group member polling once locations can be determined via subscriptions
    protected void refreshGroupMembership() {
        try {
            if (log.isDebugEnabled()) log.debug("GeoDns {} refreshing targets", this);
            if (targetEntityProvider == null)
                return;
            if (targetEntityProvider instanceof DynamicGroup)
                ((DynamicGroup) targetEntityProvider).rescanEntities();
            Set<Entity> pool = MutableSet.copyOf(targetEntityProvider instanceof Group ? ((Group)targetEntityProvider).getMembers(): targetEntityProvider.getChildren());
            if (log.isDebugEnabled()) log.debug("GeoDns {} refreshing targets, pool now {}", this, pool);
            
            boolean changed = false;
            Set<Entity> previousOnes = MutableSet.copyOf(targetHosts.keySet());
            for (Entity e: pool) {
                previousOnes.remove(e);
                changed |= addTargetHost(e);
            }
            // anything left in previousOnes is no longer applicable
            for (Entity e: previousOnes) {
                changed = true;
                removeTargetHost(e, false);
            }
            
            // do a periodic full update hourly (probably not needed)
            if (changed || Time.hasElapsedSince(lastUpdate, Duration.ONE_HOUR))
                update();
            
        } catch (Exception e) {
            log.error("Problem refreshing group membership: "+e, e);
        }
    }
    
    /**
     * Adds this host, if it is absent or if its hostname has changed.
     * 
     * For whether to use hostname or ip, see config and attributes {@link AbstractGeoDnsService#USE_HOSTNAMES}, 
     * {@link Attributes#HOSTNAME} and {@link Attributes#ADDRESS} (via {@link #inferHostname(Entity)} and {@link #inferIp(Entity)}.
     * Note that the "hostname" could infact be an IP address, if {@link #inferHostname(Entity)} returns an IP!
     * <p>
     * The "hostname" is always preferred for inferring the geo info, if it is available. The {@code USE_HOSTNAMES==false} 
     * is just used to say whether to fall back to IP if that is not available (and whether to switch the the geo-info so it
     * refs the IP instead of the hostname).
     * 
     * TODO in a future release, we may change this to explicitly set the sensor(s) to look at on the entity, and 
     * be stricter about using them in order.
     * 
     * @return true if host is added or changed
     */
    protected boolean addTargetHost(Entity entity) {
        try {
            HostGeoInfo oldGeo = targetHosts.get(entity);
            String hostname = inferHostname(entity);
            String ip = inferIp(entity);
            String addr = (getConfig(USE_HOSTNAMES) || ip == null) ? hostname : ip;
            HostGeoInfo geoE = HostGeoInfo.fromEntity(entity);
            HostGeoInfo geoH = inferHostGeoInfo(hostname, ip);
            
            if (addr == null) {
                if (entitiesWithoutHostname.add(entity)) {
                    log.debug("GeoDns ignoring {}, will continue scanning (no hostname or URL available)", entity);
                }
                return false;
            }
            
            if (Networking.isPrivateSubnet(addr)) {
                if (getConfig(INCLUDE_HOMELESS_ENTITIES)) {
                    if (entitiesWithoutGeoInfo.add(entity)) {
                        log.info("GeoDns including {}, even though {} is a private subnet (homeless entities included)", entity, addr);
                    }
                } else {
                    if (entitiesWithoutGeoInfo.add(entity)) {
                        log.warn("GeoDns ignoring {} (private subnet detected for {})", entity, addr);
                    }
                    return false;
                }
            }
            
            if (geoH == null) {
                if (getConfig(INCLUDE_HOMELESS_ENTITIES)) {
                    if (entitiesWithoutGeoInfo.add(entity)) {
                        log.info("GeoDns including {}, even though no geography info available for {})", entity, addr);
                    }
                    geoH = (geoE != null) ? geoE : HostGeoInfo.create(addr, "unknownLocation("+addr+")", 0, 0);
                } else {
                    if (entitiesWithoutGeoInfo.add(entity)) {
                        log.warn("GeoDns ignoring {} (no geography info available for {})", entity, addr);
                    }
                    return false;
                }
            }
            
            // If we already knew about it, and it hasn't changed, then nothing to do
            if (oldGeo != null && geoH.getAddress().equals(oldGeo.getAddress())) {
                return false;
            }
            
            // Check if location has lat/lon explicitly set; use geo-dns but warn if dramatically different
            if (geoE != null) {
                if ((Math.abs(geoH.latitude-geoE.latitude)>3) ||
                        (Math.abs(geoH.longitude-geoE.longitude)>3) ) {
                    log.warn("GeoDns mismatch, {} is in {} but hosts URL in {}", new Object[] {entity, geoE, geoH});
                }
            }
            
            entitiesWithoutHostname.remove(entity);
            entitiesWithoutGeoInfo.remove(entity);
            log.info("GeoDns adding "+entity+" at "+geoH+(oldGeo != null ? " (previously "+oldGeo+")" : ""));
            targetHosts.put(entity, geoH);
            return true;

        } catch (Exception ee) {
            log.warn("GeoDns ignoring {} (error analysing location, {}", entity, ee);
            return false;
        }
    }

    /** remove if host removed */
    protected boolean removeTargetHost(Entity e, boolean doUpdate) {
        if (targetHosts.remove(e) != null) {
            log.info("GeoDns removing reference to {}", e);
            if (doUpdate) update();
            return true;
        }
        return false;
    }
    
    protected void update() {
        log.debug("Full update of "+this);
        lastUpdate = System.currentTimeMillis();
        
        Map<Entity, HostGeoInfo> m;
        synchronized(targetHosts) { m = ImmutableMap.copyOf(targetHosts); }
        
        Map<String,String> entityIdToUrl = Maps.newLinkedHashMap();
        for (Map.Entry<Entity, HostGeoInfo> entry : m.entrySet()) {
            entityIdToUrl.put(entry.getKey().getId(), entry.getValue().address);
        }
        
        reconfigureService(new LinkedHashSet<HostGeoInfo>(m.values()));
        
        setAttribute(TARGETS, entityIdToUrl);
    }
    
    protected String inferHostname(Entity entity) {
        String hostname = entity.getAttribute(Attributes.HOSTNAME);
        String url = entity.getAttribute(WebAppService.ROOT_URL);
        if (url!=null) {
            try {
                URL u = new URL(url);
                
                if (hostname==null) {
                    if (!entitiesWithoutGeoInfo.contains(entity))  //don't log repeatedly
                        log.warn("GeoDns using URL {} to redirect to {} (HOSTNAME attribute is preferred, but not available)", url, entity);
                    hostname = u.getHost(); 
                }
                
                if (u.getPort() > 0 && u.getPort() != 80 && u.getPort() != 443) {
                    if (!entitiesWithoutGeoInfo.contains(entity))  //don't log repeatedly
                        log.warn("GeoDns detected non-standard port in URL {} for {}; forwarding may not work", url, entity);
                }
                
            } catch (MalformedURLException e) {
                log.warn("Invalid URL {} for entity {} in {}", new Object[] {url, entity, this});
            }
        }
        return hostname;
    }
    
    protected String inferIp(Entity entity) {
        return entity.getAttribute(Attributes.ADDRESS);
    }
    
    protected HostGeoInfo inferHostGeoInfo(String hostname, String ip) throws UnknownHostException {
        // Look up the geo-info from the hostname/ip
        HostGeoInfo geoH;
        try {
            InetAddress addr = (hostname == null) ? null : InetAddress.getByName(hostname);
            geoH = (addr == null) ? null : HostGeoInfo.fromIpAddress(addr);
        } catch (UnknownHostException e) {
            if (getConfig(USE_HOSTNAMES) || ip == null) {
                throw e;
            } else {
                if (log.isTraceEnabled()) log.trace("GeoDns failed to infer GeoInfo from hostname {}; will try with IP {} ({})", new Object[] {hostname, ip, e});
                geoH = null;
            }
        }

        // Switch to IP address if that's what we're configured to use, and it's available
        if (!getConfig(USE_HOSTNAMES) && ip != null) {
            if (geoH == null) {
                InetAddress addr = Networking.getInetAddressWithFixedName(ip);
                geoH = HostGeoInfo.fromIpAddress(addr);
                if (log.isTraceEnabled()) log.trace("GeoDns inferred GeoInfo {} from ip {} (could not infer from hostname {})", new Object[] {geoH, ip, hostname});
            } else {
                geoH = HostGeoInfo.create(ip, geoH.displayName, geoH.latitude, geoH.longitude);
                if (log.isTraceEnabled()) log.trace("GeoDns inferred GeoInfo {} from hostname {}; switching it to ip {}", new Object[] {geoH, hostname, ip});
            }
        } else {
            if (log.isTraceEnabled()) log.trace("GeoDns inferred GeoInfo {} from hostname {}", geoH, hostname);
        }
        
        return geoH;
    }
}
