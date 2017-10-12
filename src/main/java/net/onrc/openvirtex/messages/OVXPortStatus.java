/*******************************************************************************
 * Copyright 2014 Open Networking Laboratory
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package net.onrc.openvirtex.messages;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.onrc.openvirtex.elements.Mappable;
import net.onrc.openvirtex.elements.datapath.OVXBigSwitch;
import net.onrc.openvirtex.elements.datapath.OVXSwitch;
import net.onrc.openvirtex.elements.datapath.PhysicalSwitch;
import net.onrc.openvirtex.elements.link.OVXLink;
import net.onrc.openvirtex.elements.link.PhysicalLink;
import net.onrc.openvirtex.elements.network.OVXNetwork;
import net.onrc.openvirtex.elements.network.PhysicalNetwork;
import net.onrc.openvirtex.elements.port.LinkPair;
import net.onrc.openvirtex.elements.port.OVXPort;
import net.onrc.openvirtex.elements.port.PhysicalPort;
import net.onrc.openvirtex.exceptions.LinkMappingException;
import net.onrc.openvirtex.exceptions.NetworkMappingException;
import net.onrc.openvirtex.routing.SwitchRoute;
import net.onrc.openvirtex.util.OVXStatus;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.openflow.protocol.OFPhysicalPort.OFPortState;
import org.openflow.protocol.OFPortStatus;

public class OVXPortStatus extends OFPortStatus implements Virtualizable {

    private final Logger log = LogManager.getLogger(OVXPortStatus.class);
    private final Logger weblog = LogManager.getLogger("web-display-log");

    @Override
    public void virtualize(final PhysicalSwitch sw) {
        Mappable map = sw.getMap();
        PhysicalPort p = sw.getPort(this.desc.getPortNumber());
        if (p == null) {
            handlePortAdd(sw, p);
            return;
        }
        
        log.info("Received {} from switch {}", this.toString(),
                sw.getSwitchName());
        LinkPair<PhysicalLink> pair = p.getLink();
        try {
            Set<Integer> vnets = map.listVirtualNetworks().keySet();
            for (Integer tenantId : vnets) {
                /* handle vLinks/routes containing phyLink to/from this port. */
                if ((pair != null) && (pair.exists())) {
                    handleLinkChange(sw, map, pair, tenantId);
                }
                // Only do when creating OVXLinks by ourselves. In OVXBigSwitch, 
                // these statements below will not be executed.
                List<Map<Integer, OVXPort>> vports = p.getOVXPorts(tenantId);
                /* cycle through all OVXPorts for this port. */
                Iterator<Map<Integer, OVXPort>> pItr = vports.iterator();
                while (pItr.hasNext()) {
                    Map<Integer, OVXPort> mp = pItr.next();
                    if (mp == null) {
                        continue;
                    }
                    for (Map.Entry<Integer, OVXPort> pMap : mp.entrySet()) {
                        OVXPort vport = pMap.getValue();
                        if (vport == null) {
                            continue;
                        }
                        if (isReason(OFPortReason.OFPPR_DELETE)) {
                            /* try to remove OVXPort, vLinks, routes */
                            vport.unMapHost();
                            vport.handlePortDelete(this);
                            sw.removePort(p);
                        } else if (isReason(OFPortReason.OFPPR_MODIFY)) {
                            if (isState(OFPortState.OFPPS_LINK_DOWN)) {
                                /* set ports as edge, but don't remove vLinks */
                                vport.handlePortDisable(this);
                            } else if (!isState(OFPortState.OFPPS_LINK_DOWN)
                                    && ((p.getState() & OFPortState.OFPPS_LINK_DOWN
                                            .getValue()) == 1)) {
                                /*
                                 * set links to non-edge, if it was previously
                                 * disabled
                                 */
                                vport.handlePortEnable(this);
                            }
                        }
                    }
                }
            }
            
            if (isState(OFPortState.OFPPS_STP_LISTEN)
            		&& ((p.getState() & OFPortState.OFPPS_LINK_DOWN.getValue()) == 1)) 
            	PhysicalNetwork.getInstance().createLink(pair.getOutLink().getSrcPort(), pair.getOutLink().getDstPort());
            if ((isReason(OFPortReason.OFPPR_DELETE))
                            || (isReason(OFPortReason.OFPPR_MODIFY) & isState(OFPortState.OFPPS_LINK_DOWN)))
            	PhysicalNetwork.getInstance().removeLink(pair.getOutLink().getSrcPort(), pair.getOutLink().getDstPort());
            
            p.setState(this.desc.getState());
            log.info("Update state[{}] on {}/{}", 
            		this.fromStateCode(this.desc.getState()), 
            		p.getParentSwitch().getName(), p.getPortNumber());
            weblog.warn("Update state[{}] on {}/{}", 
            		this.fromStateCode(this.desc.getState()), 
            		p.getParentSwitch().getName(), p.getPortNumber());
            
        } catch (NetworkMappingException | LinkMappingException e) {
            log.warn("Couldn't process reason={} for PortStatus for port {}",
                    this.reason, p.getPortNumber());
            e.printStackTrace();
        }
    }

    private void handlePortAdd(PhysicalSwitch sw, PhysicalPort p) {
        /* add a new port to PhySwitch if add message, quit otherwise */
        if (isReason(OFPortReason.OFPPR_ADD)) {
        	// the default set it as an edge port
            p = new PhysicalPort(this.desc, sw, true);
            if (!sw.addPort(p)) {
                log.warn("Could not add new port {} to physical switch {}",
                        p.getPortNumber(), sw.getSwitchId());
            }
            log.info("Added port {} to switch {}", p.getPortNumber(),
                    sw.getSwitchId());
        }
    }

    /**
     * Handles change in internal link state, e.g., a PhysicalPort in, but not at
     * edges of, an OVXLink or SwitchRoute.
     *
     * @param map
     *            Mappable containing global information
     * @param pair
     *            the LinkPair associated with the PhysicalPort
     * @param tid
     *            the tenant ID
     * @throws LinkMappingException
     * @throws NetworkMappingException
     */
    private void handleLinkChange(PhysicalSwitch sw, Mappable map,
            LinkPair<PhysicalLink> pair, int tid) throws LinkMappingException,
            NetworkMappingException {
        PhysicalLink plink = pair.getOutLink();
        
        if (!isState(OFPortState.OFPPS_LINK_DOWN)
                && ((plink.getSrcPort().getState() & OFPortState.OFPPS_LINK_DOWN
                        .getValue()) == 1)) {
//        	log.info("reason: {}, state: {}, {}", this.reason, this.desc.getState(), plink.getSrcPort().getState());
        	weblog.info("Port {}/{} is up!", sw.getSwitchName(), plink.getSrcPort().getPortNumber());
        	weblog.info("Link {} is up!", plink);
            OVXNetwork net = map.getVirtualNetwork(tid);
            for (OVXLink link : net.getLinks()) {
                link.tryRevert(plink);
            }
            for (OVXSwitch ovxSw : net.getSwitches()) {
                if (ovxSw instanceof OVXBigSwitch) {
                    for (Map<OVXPort, SwitchRoute> routeMap : ((OVXBigSwitch) ovxSw)
                            .getRouteMap().values()) {
                        for (SwitchRoute route : routeMap.values()) {
                            route.tryRevert(plink);
                            map.getVirtualNetwork(tid).updateStatus(OVXStatus.UP, OVXStatus.LINK_UP);
                        }
                    }
                }
            }
        }

        if (map.hasOVXLinks(plink, tid)) {
            List<OVXLink> vlinks = map.getVirtualLinks(plink, tid);
            for (OVXLink vlink : vlinks) {
                if (isReason(OFPortReason.OFPPR_DELETE)) {
                    /* couldn't recover, remove link */
                    if (!vlink.tryRecovery(plink)) {
                        OVXPort vport = vlink.getSrcPort();
                        vport.unMapHost();
                        vport.handlePortDelete(this);
                        sw.removePort(plink.getSrcPort());
                    }
                }
                if (isReason(OFPortReason.OFPPR_MODIFY)) {
                    if (isState(OFPortState.OFPPS_LINK_DOWN)) {
                        /* couldn't recover, remove link */
                        if (!vlink.tryRecovery(plink)) {
                            vlink.getSrcPort().handlePortDisable(this);
                        }
                    } else if (!isState(OFPortState.OFPPS_LINK_DOWN)
                            && ((plink.getSrcPort().getState() & OFPortState.OFPPS_LINK_DOWN
                                    .getValue()) == 1)) {
                        log.debug("enabling OVXLink mapped to port {}");
                        /*
                         * try to switch back to original path, if not just
                         * bring up and hope it's working
                         */
                        if (!vlink.tryRevert(plink)) {
                            vlink.getSrcPort().handlePortEnable(this);
                        }
                    }
                }
            }
        }
        if (map.hasSwitchRoutes(plink, tid)) {
            Set<SwitchRoute> routes = new HashSet<SwitchRoute>(
                    map.getSwitchRoutes(plink, tid));
            for (SwitchRoute route : routes) {
                /*
                 * try to recover, remove route if we fail, but don't send any
                 * stat up
                 */
                if ((isReason(OFPortReason.OFPPR_DELETE))
                        || (isReason(OFPortReason.OFPPR_MODIFY) & isState(OFPortState.OFPPS_LINK_DOWN))) {
//                	log.info("reason: {}, state: {}", this.reason, this.desc.getState());
                	weblog.info("Port {}/{} is down!", sw.getSwitchName(), plink.getSrcPort().getPortNumber());
                	weblog.warn("Link {} is down!", plink);
                    if (!route.tryRecovery(plink)) {
                        route.getSrcPort().handleRouteDisable(this);
                        map.getVirtualNetwork(tid).updateStatus(OVXStatus.ERROR, OVXStatus.LINK_DOWN);
                    } else {
                    	map.getVirtualNetwork(tid).updateStatus(OVXStatus.WARN, OVXStatus.SWITCH_TO_BACKUP);
                    }
                }
            }
        }
    }

    public boolean isReason(OFPortReason reason) {
        return this.reason == reason.getReasonCode();
    }

    public boolean isState(OFPortState state) {
        return this.desc.getState() == state.getValue();
    }

    public String fromStateCode(int state) {
    	for (OFPortState s: OFPortState.values()) {
    		if (s.getValue() == state) return s.toString();
    	}
    	return "Unknown State";
    }
    
    @Override
    public String toString() {
        return "OVXPortStatus: reason["
                + OFPortReason.fromReasonCode(this.reason).name() + "]"
                + " port[" + this.desc.getPortNumber() + "]"
                + " state[" + this.fromStateCode(this.desc.getState()) + "]";
    }

}
