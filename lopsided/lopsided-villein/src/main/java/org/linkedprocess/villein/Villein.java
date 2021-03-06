/*
 * Copyright (c) 2009. The LoPSideD implementation of the Linked Process
 * protocol is an open-source project founded at the Center for Nonlinear Studies
 * at the Los Alamos National Laboratory in Los Alamos, New Mexico. Please visit
 * http://linkedprocess.org and LICENSE.txt for more information.
 */

package org.linkedprocess.villein;

import org.jivesoftware.smack.Roster;
import org.jivesoftware.smack.RosterEntry;
import org.jivesoftware.smack.filter.IQTypeFilter;
import org.jivesoftware.smack.filter.OrFilter;
import org.jivesoftware.smack.filter.PacketFilter;
import org.jivesoftware.smack.filter.PacketTypeFilter;
import org.jivesoftware.smack.packet.IQ;
import org.jivesoftware.smack.packet.Presence;
import org.jivesoftware.smack.packet.RosterPacket;
import org.jivesoftware.smack.provider.ProviderManager;
import org.jivesoftware.smackx.ServiceDiscoveryManager;
import org.linkedprocess.Jid;
import org.linkedprocess.LinkedProcess;
import org.linkedprocess.LopXmppException;
import org.linkedprocess.XmppClient;
import org.linkedprocess.farm.*;
import org.linkedprocess.villein.proxies.CloudProxy;
import org.linkedprocess.villein.proxies.CountrysideProxy;
import org.linkedprocess.villein.proxies.FarmProxy;
import org.linkedprocess.villein.proxies.VmProxy;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

/**
 * LopVillein is the primary class used when creating an LoP villein. An LoP villein is an XMPP client that is identified by a fully-qualified JID.
 * The bare JID of a villein is the villein's countryside. A villein is able to leverage the resources of an LoP cloud and thus,
 * can utilize the resources on other countrysides. For anyone wishing to make use of the computing resource of an LoP cloud, a villein is the
 * means by which this is done.
 *
 * @author Marko A. Rodriguez (http://markorodriguez.com)
 * @version LoPSideD 0.1
 */
public class Villein extends XmppClient {

    public static Logger LOGGER = LinkedProcess.getLogger(Villein.class);
    public static final String RESOURCE_PREFIX = "LoPVillein";
    public static final String STATUS_MESSAGE = "LoPSideD Villein";
    protected LinkedProcess.Status status;
    protected Dispatcher dispatcher;

    protected Set<PresenceHandler> presenceHandlers = new HashSet<PresenceHandler>();
    protected CloudProxy cloudProxy = new CloudProxy();

    /**
     * Creates a new LoP villein.
     *
     * @param server   the XMPP server to log into
     * @param port     the port that the XMPP server is listening on
     * @param username the username to log into the XMPP server with
     * @param password the password to use to log into the XMPP server with
     * @throws LopXmppException is thrown when some communication error occurs with the XMPP server
     */
    public Villein(final String server, final int port, final String username, final String password) throws LopXmppException {
        LOGGER.info("Starting " + STATUS_MESSAGE);

        ProviderManager pm = ProviderManager.getInstance();
        pm.addIQProvider(LinkedProcess.SPAWN_VM_TAG, LinkedProcess.LOP_FARM_NAMESPACE, new SpawnVmProvider());
        pm.addIQProvider(LinkedProcess.SUBMIT_JOB_TAG, LinkedProcess.LOP_FARM_NAMESPACE, new SubmitJobProvider());
        pm.addIQProvider(LinkedProcess.PING_JOB_TAG, LinkedProcess.LOP_FARM_NAMESPACE, new PingJobProvider());
        pm.addIQProvider(LinkedProcess.ABORT_JOB_TAG, LinkedProcess.LOP_FARM_NAMESPACE, new AbortJobProvider());
        pm.addIQProvider(LinkedProcess.MANAGE_BINDINGS_TAG, LinkedProcess.LOP_FARM_NAMESPACE, new ManageBindingsProvider());
        pm.addIQProvider(LinkedProcess.TERMINATE_VM_TAG, LinkedProcess.LOP_FARM_NAMESPACE, new TerminateVmProvider());
        this.logon(server, port, username, password, RESOURCE_PREFIX);
        this.dispatcher = new Dispatcher(this);
        this.initiateFeatures();
        //this.printClientStatistics();

        this.roster.setSubscriptionMode(Roster.SubscriptionMode.manual);

        PacketFilter lopFilter = new OrFilter(new IQTypeFilter(IQ.Type.RESULT), new IQTypeFilter(IQ.Type.ERROR));
        PacketFilter presenceFilter = new PacketTypeFilter(Presence.class);

        this.connection.addPacketListener(new PresencePacketListener(this), presenceFilter);
        this.connection.addPacketListener(new VilleinPacketListener(this), lopFilter);
        this.status = LinkedProcess.Status.ACTIVE;
        this.sendPresence(this.status);
    }

    public final void sendPresence(final LinkedProcess.Status status) {
        Presence presence;
        if (status == LinkedProcess.Status.ACTIVE) {
            presence = new Presence(Presence.Type.available, Villein.STATUS_MESSAGE, LinkedProcess.HIGHEST_PRIORITY, Presence.Mode.available);

        } else if (status == LinkedProcess.Status.INACTIVE) {
            presence = new Presence(Presence.Type.unavailable);
        } else {
            throw new IllegalStateException("unhandled state: " + status);
        }
        presence.setFrom(this.getJid().toString());
        this.connection.sendPacket(presence);
    }

    public void setStatus(LinkedProcess.Status status) {
        this.status = status;
    }

    public LinkedProcess.Status getStatus() {
        return this.status;
    }

    /**
     * An LoP cloud is the primary data structure which contains methods for accessing all other resources in a cloud.
     *
     * @return an LoP cloud data structure
     */
    public CloudProxy getCloudProxy() {
        return this.cloudProxy;
    }

    /**
     * An XMPP roster maintains a collection of subscriptions to bare JIDs (i.e. countrysides).
     * This collection of countrysides may contain farms and other LoP-based resources.
     */
    public void createCloudFromRoster() {
        for (RosterEntry entry : this.getRoster().getEntries()) {
            CountrysideProxy countrysideProxy = this.cloudProxy.getCountrysideProxy(new Jid(entry.getUser()));
            if (countrysideProxy == null && (entry.getType() == RosterPacket.ItemType.to || entry.getType() == RosterPacket.ItemType.both)) {
                countrysideProxy = new CountrysideProxy(new Jid(entry.getUser()));
                this.cloudProxy.addCountrysideProxy(countrysideProxy);
            }
        }
    }

    /**
     * When a unsubscription is requested, all virtual machines that this villein has access to on the countryside are terminatd.
     *
     * @param jid the jid to unsubscribe from (subscriptions are only to and from countrysides)
     */
    public void requestUnsubscription(Jid jid) {
        super.requestUnsubscription(jid);
        CountrysideProxy countrysideProxy = this.cloudProxy.getCountrysideProxy(jid);
        if (countrysideProxy != null) {
            for (FarmProxy farmProxy : countrysideProxy.getFarmProxies()) {
                for (VmProxy vmProxy : farmProxy.getVmProxies()) {
                    if (vmProxy.getVmId() != null) {
                        vmProxy.terminateVm(null, null);
                    }
                }
            }
        }
        this.cloudProxy.removeCountrysideProxy(jid);
    }

    /**
     * Adds the identity name and type of the Villein to its disco#info document.
     */
    protected void initiateFeatures() {
        super.initiateFeatures();
        ServiceDiscoveryManager.setIdentityName(Villein.RESOURCE_PREFIX);
        ServiceDiscoveryManager.setIdentityType(LinkedProcess.DISCO_BOT);
    }

    public Dispatcher getDispatcher() {
        return this.dispatcher;
    }

    public void addPresenceHandler(PresenceHandler presenceHandler) {
        this.presenceHandlers.add(presenceHandler);
    }

    public Set<PresenceHandler> getPresenceHandlers() {
        return this.presenceHandlers;
    }

    public void removePresenceHandler(PresenceHandler presenceHandler) {
        this.presenceHandlers.remove(presenceHandler);
    }
}
