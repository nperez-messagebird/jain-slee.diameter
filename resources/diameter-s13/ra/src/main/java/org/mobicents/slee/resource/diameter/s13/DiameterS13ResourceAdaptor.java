/*
 * JBoss, Home of Professional Open Source
 * Copyright 2011, Red Hat, Inc. and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.mobicents.slee.resource.diameter.s13;

import static org.jdiameter.client.impl.helpers.Parameters.MessageTimeOut;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.List;

import javax.management.ObjectName;
import javax.naming.OperationNotSupportedException;
import javax.slee.Address;
import javax.slee.facilities.EventLookupFacility;
import javax.slee.facilities.Tracer;
import javax.slee.resource.ActivityFlags;
import javax.slee.resource.ActivityHandle;
import javax.slee.resource.ConfigProperties;
import javax.slee.resource.EventFlags;
import javax.slee.resource.FailureReason;
import javax.slee.resource.FireableEventType;
import javax.slee.resource.InvalidConfigurationException;
import javax.slee.resource.Marshaler;
import javax.slee.resource.ReceivableService;
import javax.slee.resource.ResourceAdaptor;
import javax.slee.resource.ResourceAdaptorContext;
import javax.slee.resource.SleeEndpoint;

import net.java.slee.resource.diameter.Validator;
import net.java.slee.resource.diameter.base.CreateActivityException;
import net.java.slee.resource.diameter.base.DiameterActivity;
import net.java.slee.resource.diameter.base.DiameterAvpFactory;
import net.java.slee.resource.diameter.base.events.DiameterMessage;
import net.java.slee.resource.diameter.base.events.avp.DiameterIdentity;
import net.java.slee.resource.diameter.s13.S13AVPFactory;
import net.java.slee.resource.diameter.s13.S13ClientSessionActivity;
import net.java.slee.resource.diameter.s13.S13MessageFactory;
import net.java.slee.resource.diameter.s13.S13Provider;
import net.java.slee.resource.diameter.s13.S13ServerSessionActivity;
import net.java.slee.resource.diameter.s13.events.MEIdentityCheckRequest;

import org.jboss.mx.util.MBeanServerLocator;
import org.jdiameter.api.Answer;
import org.jdiameter.api.ApplicationId;
import org.jdiameter.api.AvpDataException;
import org.jdiameter.api.EventListener;
import org.jdiameter.api.IllegalDiameterStateException;
import org.jdiameter.api.InternalException;
import org.jdiameter.api.Message;
import org.jdiameter.api.Peer;
import org.jdiameter.api.PeerTable;
import org.jdiameter.api.Request;
import org.jdiameter.api.Session;
import org.jdiameter.api.SessionFactory;
import org.jdiameter.api.Stack;
import org.jdiameter.api.app.AppSession;
import org.jdiameter.api.app.StateChangeListener;
import org.jdiameter.api.s13.ClientS13Session;
import org.jdiameter.api.s13.ServerS13Session;
import org.jdiameter.api.sh.ServerShSession;
import org.jdiameter.client.api.ISessionFactory;
import org.mobicents.diameter.stack.DiameterListener;
import org.mobicents.diameter.stack.DiameterStackMultiplexerMBean;
import org.mobicents.slee.resource.diameter.DiameterActivityManagement;
import org.mobicents.slee.resource.diameter.LocalDiameterActivityManagement;
import org.mobicents.slee.resource.diameter.ValidatorImpl;
import org.mobicents.slee.resource.diameter.base.DiameterActivityHandle;
import org.mobicents.slee.resource.diameter.base.DiameterActivityImpl;
import org.mobicents.slee.resource.diameter.base.DiameterAvpFactoryImpl;
import org.mobicents.slee.resource.diameter.base.DiameterBaseMarshaler;
import org.mobicents.slee.resource.diameter.base.DiameterMessageFactoryImpl;
import org.mobicents.slee.resource.diameter.base.EventIDFilter;
import org.mobicents.slee.resource.diameter.base.events.ErrorAnswerImpl;
import org.mobicents.slee.resource.diameter.base.events.ExtensionDiameterMessageImpl;
import org.mobicents.slee.resource.diameter.base.handlers.AuthorizationSessionFactory;
import org.mobicents.slee.resource.diameter.base.handlers.DiameterRAInterface;
import org.mobicents.slee.resource.diameter.s13.events.MEIdentityCheckAnswerImpl;
import org.mobicents.slee.resource.diameter.s13.events.MEIdentityCheckRequestImpl;
import org.mobicents.slee.resource.diameter.s13.handlers.S13SessionFactory;

/**
 * Diameter S13 Resource Adaptor
 *
 * @author <a href="mailto:brainslog@gmail.com"> Alexandre Mendonca </a>
 * @author <a href="mailto:baranowb@gmail.com"> Bartosz Baranowski </a>
 * @author <a href="mailto:richard.good@smilecoms.com"> Richard Good </a>
 * @author <a href="mailto:paul.carter-brown@smilecoms.com"> Paul Carter-Brown </a>
 */
public class DiameterS13ResourceAdaptor implements ResourceAdaptor, DiameterListener, DiameterRAInterface {

  private static final long serialVersionUID = 1L;

  // Config Properties Names ---------------------------------------------
  private static final String AUTH_APPLICATION_IDS = "authApplicationIds";

  // Config Properties Values --------------------------------------------
  private List<ApplicationId> authApplicationIds;

  /**
   * caches the eventIDs, avoiding lookup in container
   */
  public final EventIDCache eventIdCache = new EventIDCache();

  /**
   * tells the RA if an event with a specified ID should be filtered or not
   */
  private final EventIDFilter eventIDFilter = new EventIDFilter();

  /**
   * The ResourceAdaptorContext interface is implemented by the SLEE. It provides the Resource
   * Adaptor with the required capabilities in the SLEE to execute its work. The ResourceAdaptorCon-
   * text object holds references to a number of objects that are of interest to many Resource Adaptors. A
   * resource adaptor object is provided with a ResourceAdaptorContext object when the setResour-
   * ceAdaptorContext method of the ResourceAdaptor interface is invoked on the resource adaptor
   * object. 
   */
  private ResourceAdaptorContext raContext;

  /**
   * The SLEE endpoint defines the contract between the SLEE and the resource
   * adaptor that enables the resource adaptor to deliver events
   * asynchronously to SLEE endpoints residing in the SLEE. This contract
   * serves as a generic contract that allows a wide range of resources to be
   * plugged into a SLEE environment via the resource adaptor architecture.
   * For further information see JSLEE v1.1 Specification Page 307 The
   * sleeEndpoint will be initialized in entityCreated() method.
   */
  private transient SleeEndpoint sleeEndpoint = null;

  /**
   * A tracer is represented in the SLEE by the Tracer interface. Notification sources access the Tracer Facil-
   * ity through a Tracer object that implements the Tracer interface. A Tracer object can be obtained by
   * SBBs via the SbbContext interface, by resource adaptor entities via the ResourceAdaptorContext
   * interface, and by profiles via the ProfileContext interface. 
   */
  private Tracer tracer;

  private DiameterBaseMarshaler marshaler/*= new DiameterBaseMarshaler()*/;

  // Diameter Specific Properties ----------------------------------------
  private Stack stack;
  private long messageTimeout = 5000;
  private long activityRemoveDelay = 30000;

  private ObjectName diameterMultiplexerObjectName = null;
  private DiameterStackMultiplexerMBean diameterMux = null;

  // Base Factories
  private DiameterAvpFactory baseAvpFactory = null;
  private SessionFactory sessionFactory = null;

  // S13 Specific Factories
  private S13AVPFactory s13AvpFactory;
  private S13MessageFactory s13MessageFactory;
  private S13SessionFactory s13SessionFactory = null;

  /**
   * The EventLookupFacility is used to look up the event id of incoming events
   */
  private transient EventLookupFacility eventLookup = null;

  /**
   * The list of activities stored in this resource adaptor. If this resource
   * adaptor were a distributed and highly available solution, this storage
   * were one of the candidates for distribution.
   */
  private transient DiameterActivityManagement activities = null;

  /**
   * A link to the DiameterProvider which then will be exposed to Sbbs
   */
  private transient S13ProviderImpl raProvider = null;

  protected transient AuthorizationSessionFactory authSessionFactory = null;
  protected transient SessionFactory proxySessionFactory = null;

  /**
   * For all events we are interested in knowing when the event failed to be processed
   */
  private static final int EVENT_FLAGS = getEventFlags();

  private static int getEventFlags() {
    int eventFlags = EventFlags.REQUEST_EVENT_UNREFERENCED_CALLBACK;
    eventFlags = EventFlags.setRequestProcessingFailedCallback(eventFlags);
    eventFlags = EventFlags.setRequestProcessingSuccessfulCallback(eventFlags);
    return eventFlags;
  }
  private static final int DEFAULT_ACTIVITY_FLAGS = ActivityFlags.setRequestSleeActivityGCCallback(ActivityFlags.REQUEST_ENDED_CALLBACK);

  public DiameterS13ResourceAdaptor() {
    // TODO: Initialize any default values.
  }

  // Lifecycle methods ---------------------------------------------------
  public void setResourceAdaptorContext(ResourceAdaptorContext context) {
    this.raContext = context;
    this.tracer = context.getTracer("DiameterS13ResourceAdaptor");
    this.sleeEndpoint = context.getSleeEndpoint();
    this.eventLookup = context.getEventLookupFacility();
    this.raProvider = new S13ProviderImpl(this);
  }

  public void unsetResourceAdaptorContext() {
    this.raContext = null;
    this.tracer = null;
    this.sleeEndpoint = null;
    this.eventLookup = null;
  }

  public void raActive() {
    if (tracer.isFineEnabled()) {
      tracer.fine("Diameter S13 RA :: raActive.");
    }

    try {
      if (tracer.isInfoEnabled()) {
        tracer.info("Activating Diameter S13 RA Entity");
      }

      this.diameterMultiplexerObjectName = new ObjectName("diameter.mobicents:service=DiameterStackMultiplexer");

      Object object = null;

      if (ManagementFactory.getPlatformMBeanServer().isRegistered(this.diameterMultiplexerObjectName)) {
        // trying to get via MBeanServer
        object = ManagementFactory.getPlatformMBeanServer().invoke(this.diameterMultiplexerObjectName, "getMultiplexerMBean", new Object[]{}, new String[]{});
        if (tracer.isInfoEnabled()) {
          tracer.info("Trying to get via Platform MBeanServer: " + this.diameterMultiplexerObjectName + ", object: " + object);
        }
      } else {
        // trying to get via locateJBoss
        object = MBeanServerLocator.locateJBoss().invoke(this.diameterMultiplexerObjectName, "getMultiplexerMBean", new Object[]{}, new String[]{});
        if (tracer.isInfoEnabled()) {
          tracer.info("Trying to get via JBoss MBeanServer: " + this.diameterMultiplexerObjectName + ", object: " + object);
        }
      }

      if (object != null && object instanceof DiameterStackMultiplexerMBean) {
        this.diameterMux = (DiameterStackMultiplexerMBean) object;
      }

      // Initialize the protocol stack
      initStack();

      // Initialize activities mgmt
      initActivitiesMgmt();

      // Initialize factories
      this.baseAvpFactory = new DiameterAvpFactoryImpl();

      this.s13AvpFactory = new S13AVPFactoryImpl(baseAvpFactory);
      this.s13MessageFactory = new S13MessageFactoryImpl(stack);

      // Set the first configured Application-Id as default for message factory
      ApplicationId firstAppId = authApplicationIds.get(0);
      ((S13MessageFactoryImpl)this.s13MessageFactory).setApplicationId(firstAppId.getVendorId(), firstAppId.getAuthAppId());

      // Setup session factories
      this.sessionFactory = this.stack.getSessionFactory();
      this.s13SessionFactory = new S13SessionFactory(this, messageTimeout, sessionFactory);

      ((ISessionFactory) sessionFactory).registerAppFacory(ServerS13Session.class, s13SessionFactory);
      ((ISessionFactory) sessionFactory).registerAppFacory(ClientS13Session.class, s13SessionFactory);
    }
    catch (Exception e) {
      tracer.severe("Error Activating Diameter S13 RA Entity", e);
    }
  }

  public void raStopping() {
    if (tracer.isFineEnabled()) {
      tracer.fine("Diameter S13 RA :: raStopping.");
    }

    try {
      diameterMux.unregisterListener(this);
    }
    catch (Exception e) {
      tracer.severe("Failed to unregister S13 RA from Diameter Mux.", e);
    }

    if (tracer.isInfoEnabled()) {
      tracer.info("Diameter S13 RA :: raStopping completed.");
    }
  }

  public void raInactive() {
    if (tracer.isFineEnabled()) {
      tracer.fine("Diameter S13 RA :: raInactive.");
    }

    activities = null;

    if (tracer.isInfoEnabled()) {
      tracer.info("Diameter S13 RA :: raInactive completed.");
    }
  }

  public void raConfigure(ConfigProperties properties) {
    parseApplicationIds((String) properties.getProperty(AUTH_APPLICATION_IDS).getValue());
  }

  private void parseApplicationIds(String appIdsStr) {
    if (appIdsStr != null) {
      appIdsStr = appIdsStr.replaceAll(" ", "");

      String[] appIdsStrings = appIdsStr.split(",");

      List<ApplicationId> appIds = new ArrayList<ApplicationId>();

      for (String appId : appIdsStrings) {
        String[] vendorAndAppId = appId.split(":");
        appIds.add(ApplicationId.createByAuthAppId(Long.valueOf(vendorAndAppId[0]), Long.valueOf(vendorAndAppId[1])));
      }

      authApplicationIds = appIds;
    }
  }

  public void raUnconfigure() {
    // Clean up!
    this.activities = null;
    this.raContext = null;
    this.eventLookup = null;
    this.raProvider = null;
    this.sleeEndpoint = null;
    this.stack = null;
  }

  // Configuration management methods ------------------------------------
  public void raVerifyConfiguration(ConfigProperties properties) throws InvalidConfigurationException {
    // NOP
  }

  public void raConfigurationUpdate(ConfigProperties properties) {
    // this ra does not support config update while entity is active    
  }

  // Interface access methods -------------------------------------------- 
  public Object getResourceAdaptorInterface(String className) {
    // this ra implements a single ra type
    return raProvider;
  }

  /*
   * (non-Javadoc)
   * @see javax.slee.resource.ResourceAdaptor#getMarshaler()
   */
  public Marshaler getMarshaler() {
    return this.marshaler;
  }

  // Event filtering methods ---------------------------------------------
  public void serviceActive(ReceivableService serviceInfo) {
    eventIDFilter.serviceActive(serviceInfo);
  }

  public void serviceStopping(ReceivableService serviceInfo) {
    eventIDFilter.serviceStopping(serviceInfo);
  }

  public void serviceInactive(ReceivableService serviceInfo) {
    eventIDFilter.serviceInactive(serviceInfo);
  }

  // Mandatory callback methods ------------------------------------------
  public void queryLiveness(ActivityHandle handle) {
    if (tracer.isInfoEnabled()) {
      tracer.info("Diameter S13 RA :: queryLiveness :: handle[" + handle + "].");
    }
    if (!(handle instanceof DiameterActivityHandle)) {
      return;
    }

    DiameterActivityImpl activity = (DiameterActivityImpl) activities.get((DiameterActivityHandle) handle);

    if (activity != null && !activity.isValid()) {
      try {
        sleeEndpoint.endActivity(handle);
      }
      catch (Exception e) {
        tracer.severe("Failure ending non-live activity.", e);
      }
    }
  }

  public Object getActivity(ActivityHandle handle) {
    if (tracer.isFineEnabled()) {
      tracer.fine("Diameter S13 RA :: getActivity :: handle[" + handle + "].");
    }
    if (!(handle instanceof DiameterActivityHandle)) {
      return null;
    }
    return this.activities.get((DiameterActivityHandle) handle);
  }

  public ActivityHandle getActivityHandle(Object activity) {
    if (tracer.isFineEnabled()) {
      tracer.fine("Diameter S13 RA :: getActivityHandle :: activity[" + activity + "].");
    }

    if (!(activity instanceof DiameterActivity)) {
      return null;
    }

    DiameterActivityImpl inActivity = (DiameterActivityImpl) activity;

    return inActivity.getActivityHandle();
  }

  public void administrativeRemove(ActivityHandle handle) {
    // TODO what to do here?
  }

  // Optional callback methods -------------------------------------------
  public void eventProcessingFailed(ActivityHandle handle, FireableEventType eventType, Object event, Address address, ReceivableService service, int flags, FailureReason reason) {
    if (tracer.isInfoEnabled()) {
      tracer.info("Diameter S13 RA :: eventProcessingFailed :: handle[" + handle + "], eventType[" + eventType + "], event[" + event + "], address[" + address + "], flags[" + flags + "], reason[" + reason + "].");
    }
  }

  public void eventProcessingSuccessful(ActivityHandle handle, FireableEventType eventType, Object event, Address address, ReceivableService service, int flags) {
    if (tracer.isInfoEnabled()) {
      tracer.info("Diameter S13 RA :: eventProcessingSuccessful :: handle[" + handle + "], eventType[" + eventType + "], event[" + event + "], address[" + address + "], flags[" + flags + "].");
    }
  }

  public void eventUnreferenced(ActivityHandle handle, FireableEventType eventType, Object event, Address address, ReceivableService service, int flags) {
    if (tracer.isFineEnabled()) {
      tracer.fine("Diameter S13 RA :: eventUnreferenced :: handle[" + handle + "], eventType[" + eventType + "], event[" + event + "], address[" + address + "], service[" + service + "], flags[" + flags + "].");
    }
  }

  public void activityEnded(ActivityHandle handle) {
    tracer.info("Diameter S13 RA :: activityEnded :: handle[" + handle + ".");
    if (this.activities != null) {
      synchronized (this.activities) {
        this.activities.remove((DiameterActivityHandle) handle);
      }
    }
  }

  public void startActivityRemoveTimer(DiameterActivityHandle handle) {
    this.activities.startActivityRemoveTimer(handle);
  }

  public void stopActivityRemoveTimer(DiameterActivityHandle handle) {
    this.activities.stopActivityRemoveTimer(handle);
  }

  public void activityUnreferenced(ActivityHandle handle) {
    if (tracer.isFineEnabled()) {
      tracer.fine("Diameter S13 RA :: activityUnreferenced :: handle[" + handle + "].");
    }
    if (!(handle instanceof DiameterActivityHandle)) {
      return;
    }
    this.activityEnded(handle);
  }

  // Event and Activities management -------------------------------------
  public boolean fireEvent(Object event, ActivityHandle handle, FireableEventType eventID, Address address, boolean useFiltering, boolean transacted) {
    if (useFiltering && eventIDFilter.filterEvent(eventID)) {
      if (tracer.isFineEnabled()) {
        tracer.fine("Event " + eventID + " filtered");
      }
    }
    else if (eventID == null) {
      tracer.severe("Event ID for " + eventID + " is unknown, unable to fire.");
    }
    else {
      if (tracer.isFineEnabled()) {
        tracer.fine("Firing event " + event + " on handle " + handle);
      }
      try {
        /* TODO: Support transacted fire of events when in cluster
        if (transacted){
          this.raContext.getSleeEndpoint().fireEventTransacted(handle, eventID, event, address, null, EVENT_FLAGS);
        }
        else */ {
          this.raContext.getSleeEndpoint().fireEvent(handle, eventID, event, address, null, EVENT_FLAGS);
        }
        return true;
      }
      catch (Exception e) {
        tracer.severe("Error firing event.", e);
      }
    }

    return false;
  }

  /*
   * (non-Javadoc)
   * @see org.mobicents.slee.resource.diameter.base.handlers.BaseSessionCreationListener#fireEvent(java.lang.String, org.jdiameter.api.Request, org.jdiameter.api.Answer)
   */
  public void fireEvent(String sessionId, Message message) {
    DiameterMessage event = (DiameterMessage) createEvent(message);

    FireableEventType eventId = eventIdCache.getEventId(eventLookup, message);

    this.fireEvent(event, getActivityHandle(sessionId), eventId, null, true, message.isRequest());
  }

  public void endActivity(DiameterActivityHandle handle) {
    sleeEndpoint.endActivity(handle);
  }

  public void update(DiameterActivityHandle handle, DiameterActivity activity) {
    activities.update(handle, activity);
  }

  /**
   * Create Event object from a JDiameter message (request or answer)
   * 
   * @return a DiameterMessage object wrapping the request/answer
   * @throws OperationNotSupportedException
   */
  private DiameterMessage createEvent(Message message) {
    if (message == null) {
      throw new NullPointerException("Message argument cannot be null while creating event.");
    }

    int commandCode = message.getCommandCode();

    if (message.isError()) {
      return new ErrorAnswerImpl(message);
    }

    boolean isRequest = message.isRequest();

    switch (commandCode) {
      case MEIdentityCheckRequest.COMMAND_CODE:
        return isRequest ? new MEIdentityCheckRequestImpl(message) : new MEIdentityCheckAnswerImpl(message);
      default:
        return new ExtensionDiameterMessageImpl(message);
    }
  }

  // Session Management --------------------------------------------------
  /**
   * Method for performing tasks when activity is created, such as informing SLEE about it and storing into internal map.
   * 
   * @param ac the activity that has been created
   */
  private void addActivity(DiameterActivity ac, boolean suspended) {
    try {
      // Inform SLEE that Activity Started
      DiameterActivityImpl activity = (DiameterActivityImpl) ac;

      if (suspended) {
        sleeEndpoint.startActivitySuspended(activity.getActivityHandle(), activity, DEFAULT_ACTIVITY_FLAGS);
      }
      else {
        sleeEndpoint.startActivity(activity.getActivityHandle(), activity, DEFAULT_ACTIVITY_FLAGS);
      }

      // Set the listener
      activity.setSessionListener(this);

      // Put it into our activites map
      activities.put(activity.getActivityHandle(), activity);

      if (tracer.isInfoEnabled()) {
        tracer.info("Activity started [" + activity.getActivityHandle() + "]");
      }
    }
    catch (Exception e) {
      tracer.severe("Error creating activity", e);

      throw new RuntimeException("Error creating activity", e);
    }
  }

  // Private Methods -----------------------------------------------------

  /**
   * Initializes the RA Diameter Stack.
   * 
   * @throws Exception
   */
  private synchronized void initStack() throws Exception {
    // Register in the Mux as an app listener.
    this.diameterMux.registerListener(this, (ApplicationId[]) authApplicationIds.toArray(new ApplicationId[authApplicationIds.size()]));

    // Get the stack (should not mess with)
    this.stack = this.diameterMux.getStack();
    this.messageTimeout = stack.getMetaData().getConfiguration().getLongValue(MessageTimeOut.ordinal(), (Long) MessageTimeOut.defValue());

    if (tracer.isInfoEnabled()) {
      tracer.info("Diameter S13 RA :: Successfully initialized stack.");
    }
  }

  private void initActivitiesMgmt() {
    this.activities = new LocalDiameterActivityManagement(this.raContext, activityRemoveDelay);
  }

  /**
   * Create the Diameter Activity Handle for an given session id
   * 
   * @param sessionId the session identifier to create the activity handle from
   * @return a DiameterActivityHandle for the provided sessionId
   */
  protected DiameterActivityHandle getActivityHandle(String sessionId) {
    return new DiameterActivityHandle(sessionId);
  }

  // NetworkReqListener Implementation -----------------------------------

  /*
   * (non-Javadoc)
   * @see org.jdiameter.api.NetworkReqListener#processRequest(org.jdiameter.api.Request)
   */
  public Answer processRequest(Request request) {
    try {
      if (request == null) {
        tracer.severe("Request is null");
      }
      if (raProvider == null) {
        tracer.severe("raProvider is null");
      }
      raProvider.createActivity(request);
    }
    catch (Throwable e) {
      tracer.severe(e.getMessage(), e);
    }

    // returning null so we can answer later
    return null;
  }

  /*
   * (non-Javadoc)
   * @see org.jdiameter.api.EventListener#receivedSuccessMessage(org.jdiameter.api.Message, org.jdiameter.api.Message)
   */
  public void receivedSuccessMessage(Request request, Answer answer) {
    if (tracer.isFineEnabled()) {
      tracer.fine("Diameter S13 RA :: receivedSuccessMessage :: " + "Request[" + request + "], Answer[" + answer + "].");
    }

    try {
      if (tracer.isInfoEnabled()) {
        tracer.info("Received Message Result-Code: " + answer.getResultCode().getUnsigned32());
      }
    }
    catch (AvpDataException ignore) {
      // ignore, this was just for informational purposes...
    }
  }

  /*
   * (non-Javadoc)
   * @see org.jdiameter.api.EventListener#timeoutExpired(org.jdiameter.api.Message)
   */
  public void timeoutExpired(Request request) {
    if (tracer.isInfoEnabled()) {
      tracer.info("Diameter S13 RA :: timeoutExpired :: Request[" + request + "].");
    }

    try {
      // Message delivery timed out - we have to remove activity
      ((DiameterActivity) getActivity(getActivityHandle(request.getSessionId()))).endActivity();
    }
    catch (Exception e) {
      tracer.severe("Failure processing timeout message.", e);
    }
  }

  // S13 Session Creation Listener --------------------------------------

  public void sessionCreated(ServerS13Session session) {
    S13MessageFactoryImpl sessionMsgFactory = new S13MessageFactoryImpl(session.getSessions().get(0), stack, new DiameterIdentity[]{});

    // Set the first configured Application-Id as default for message factory
    ApplicationId firstAppId = authApplicationIds.get(0);
    sessionMsgFactory.setApplicationId(firstAppId.getVendorId(), firstAppId.getAuthAppId());

    S13ServerSessionImpl serverActivity = new S13ServerSessionImpl(sessionMsgFactory, s13AvpFactory, session, this, null, null, stack);
    //session.addStateChangeNotification(serverActivity);
    //addActivity(serverActivity);
    serverActivity.setSessionListener(this);
  }

  public void sessionCreated(ClientS13Session session) {
    S13MessageFactoryImpl sessionMsgFactory = new S13MessageFactoryImpl(session.getSessions().get(0), stack, new DiameterIdentity[]{});

    // Set the first configured Application-Id as default for message factory
    ApplicationId firstAppId = authApplicationIds.get(0);
    sessionMsgFactory.setApplicationId(firstAppId.getVendorId(), firstAppId.getAuthAppId());

    S13ClientSessionImpl clientActivity = new S13ClientSessionImpl(sessionMsgFactory, s13AvpFactory, session, this, null, null, stack);
    //session.addStateChangeNotification(serverActivity);
    //addActivity(serverActivity);
    clientActivity.setSessionListener(this);
  }

  public void sessionCreated(Session session) {
    DiameterMessageFactoryImpl sessionMsgFactory = new DiameterMessageFactoryImpl(session, stack, null, null);
    DiameterActivityImpl activity = new DiameterActivityImpl(sessionMsgFactory, baseAvpFactory, session, this, null, null);

    // TODO: Do we need to manage session?
    //session.addStateChangeNotification(activity);
    activity.setSessionListener(this);
    addActivity(activity, false /*true*/);
  }

  /*
   * (non-Javadoc)
   * @see org.mobicents.slee.resource.diameter.s13.handlers.S13SessionCreationListener#getSupportedApplications()
   */
  public ApplicationId[] getSupportedApplications() {
    return (ApplicationId[]) authApplicationIds.toArray();
  }

  /* (non-Javadoc)
   * @see org.mobicents.slee.resource.diameter.s13.handlers.S13SessionCreationListener#stateChanged(org.jdiameter.api.app.AppSession, java.lang.Enum, java.lang.Enum)
   */
  public void stateChanged(AppSession source, Enum oldState, Enum newState) {
    DiameterActivityHandle dah = getActivityHandle(source.getSessionId());
    Object activity = getActivity(dah);
    if (activity != null) {
      if (source instanceof ServerShSession) {
        try {
          //damn, no common, do something unexpected
          StateChangeListener<AppSession> scl = (StateChangeListener<AppSession>) activity;
          scl.stateChanged(source, oldState, newState);
        }
        catch (Exception e) {
          tracer.warning("Failed to deliver state, for: " + dah + " on stateChanged( " + source + ", " + oldState + ", " + newState + " )", e);
        }
      }
    }
    else {
      tracer.warning("No activity for: " + dah + " on stateChanged( " + source + ", " + oldState + ", " + newState + " )");
    }
  }

  // Provider Implementation ---------------------------------------------
  private class S13ProviderImpl implements S13Provider {

    protected DiameterS13ResourceAdaptor ra;
    protected Validator validator = new ValidatorImpl();

    /**
     * Constructor.
     * 
     * @param s13ResourceAdaptor The resource adaptor for this Provider.
     */
    public S13ProviderImpl(DiameterS13ResourceAdaptor s13ResourceAdaptor) {
      this.ra = s13ResourceAdaptor;
    }

    private DiameterActivity createActivity(Message message) throws CreateActivityException {
      DiameterActivity activity = activities.get(getActivityHandle(message.getSessionId()));
      if (activity == null) {
        if (message.isRequest()) {
          switch(message.getCommandCode()) {
            case MEIdentityCheckRequest.COMMAND_CODE:
              return createS13ServerSessionActivity((Request) message);
          }
        }
        else {
          throw new IllegalStateException("Got answer, there should already be activity.");
        }
      }

      return activity;
    }

    private DiameterActivity createS13ServerSessionActivity(Request request) throws CreateActivityException {
      ServerS13Session session = null;

      try {
        String sessionId = request == null ? null : request.getSessionId();
        //tracer.fine("Session ID is " + sessionId);
        ApplicationId appId = request.getApplicationIdAvps().isEmpty() ? null : request.getApplicationIdAvps().iterator().next();
        //tracer.fine("App ID is " + appId);
        session = ((ISessionFactory) stack.getSessionFactory()).getNewAppSession(sessionId, appId, ServerS13Session.class, request);

        if (session == null) {
          throw new CreateActivityException("Got NULL Session while creating S13 Server Activity");
        }
      }
      catch (InternalException e) {
        throw new CreateActivityException("Internal exception while creating S13 Server Activity", e);
      }
      catch (IllegalDiameterStateException e) {
        throw new CreateActivityException("Illegal Diameter State exception while creating S13 Server Activity", e);
      }

      S13ServerSessionImpl activity = new S13ServerSessionImpl(ra.s13MessageFactory, ra.s13AvpFactory, session, (EventListener<Request, Answer>) session, (DiameterIdentity) null, (DiameterIdentity) null, stack);
      addActivity(activity, false);

      if (request != null) {
        switch (request.getCommandCode()) {
          case MEIdentityCheckRequest.COMMAND_CODE:
            activity.fetchSessionData(new MEIdentityCheckRequestImpl(request));
            break;
        }

        ((org.jdiameter.server.impl.app.s13.S13ServerSessionImpl) session).processRequest(request);
      }

      return activity;
    }

    private DiameterActivity createS13ClientSessionActivity(Request request) throws CreateActivityException {
      ClientS13Session session = null;

      try {
        String sessionId = request == null ? null : request.getSessionId();
        //tracer.fine("Session ID is " + sessionId);
        ApplicationId appId = request.getApplicationIdAvps().isEmpty() ? null : request.getApplicationIdAvps().iterator().next();
        //tracer.fine("App ID is " + appId);
        session = ((ISessionFactory) stack.getSessionFactory()).getNewAppSession(sessionId, appId, ClientS13Session.class, request);

        if (session == null) {
          throw new CreateActivityException("Got NULL Session while creating S13 Client Activity");
        }
      }
      catch (InternalException e) {
        throw new CreateActivityException("Internal exception while creating S13 Client Activity", e);
      }
      catch (IllegalDiameterStateException e) {
        throw new CreateActivityException("Illegal Diameter State exception while creating S13 Client Activity", e);
      }

      S13ClientSessionImpl activity = new S13ClientSessionImpl(ra.s13MessageFactory, ra.s13AvpFactory, session, (EventListener<Request, Answer>) session, (DiameterIdentity) null, (DiameterIdentity) null, stack);
      addActivity(activity, false);

      if (request != null) {
        switch (request.getCommandCode()) {
          case MEIdentityCheckRequest.COMMAND_CODE:
            activity.fetchSessionData(new MEIdentityCheckRequestImpl(request));
            break;
        }

        ((org.jdiameter.client.impl.app.s13.S13ClientSessionImpl) session).processRequest(request);
      }

      return activity;
    }

    public S13ServerSessionActivity createS13ServerSessionActivity(DiameterIdentity destinationHost, DiameterIdentity destinationRealm) throws CreateActivityException {
      try {
        ServerS13Session session = ((ISessionFactory) stack.getSessionFactory()).getNewAppSession(null, ApplicationId.createByAuthAppId(10415L, 16777251L), ServerS13Session.class);
        S13ServerSessionImpl activity = new S13ServerSessionImpl(ra.s13MessageFactory, ra.s13AvpFactory, session, (EventListener<Request, Answer>) session, destinationHost, destinationRealm, stack);
        addActivity(activity, false);
        return activity;
      }
      catch (Exception e) {
        throw new CreateActivityException("Internal exception while creating S13 Server Activity", e);
      }
    }

    public S13ClientSessionActivity createS13ClientSessionActivity(DiameterIdentity destinationHost, DiameterIdentity destinationRealm) throws CreateActivityException {
      try {
        ClientS13Session session = ((ISessionFactory) stack.getSessionFactory()).getNewAppSession(null, ApplicationId.createByAuthAppId(10415L, 16777251L), ClientS13Session.class);
        S13ClientSessionImpl activity = new S13ClientSessionImpl(ra.s13MessageFactory, ra.s13AvpFactory, session, (EventListener<Request, Answer>) session, destinationHost, destinationRealm, stack);
        addActivity(activity, false);
        return activity;
      }
      catch (Exception e) {
        throw new CreateActivityException("Internal exception while creating S13 Client Activity", e);
      }
    }

    public S13MessageFactory getS13MessageFactory() {
      return ra.s13MessageFactory;
    }

    public S13AVPFactory getS13AVPFactory() {
      return ra.s13AvpFactory;
    }

    public DiameterIdentity[] getConnectedPeers() {
      return ra.getConnectedPeers();
    }

    public int getPeerCount() {
      return ra.getConnectedPeers().length;
    }

    public S13ServerSessionActivity createS13ServerSessionActivity() throws CreateActivityException {
      return createS13ServerSessionActivity(null, null);
    }

    public S13ClientSessionActivity createS13ClientSessionActivity() throws CreateActivityException {
      return createS13ClientSessionActivity(null, null);
    }

    /* (non-Javadoc)
     * @see net.java.slee.resource.diameter.s13.S13Provider#getValidator()
     */
    public Validator getValidator() {
      return this.validator;
    }
  }

  public DiameterIdentity[] getConnectedPeers() {
    if (this.stack != null) {
      try {
        // Get the list of peers from the stack
        List<Peer> peers = stack.unwrap(PeerTable.class).getPeerTable();

        DiameterIdentity[] result = new DiameterIdentity[peers.size()];

        int i = 0;

        // Get each peer from the list and make a DiameterIdentity
        for (Peer peer : peers) {
          DiameterIdentity identity = new DiameterIdentity(peer.getUri().toString());

          result[i++] = identity;
        }

        return result;
      }
      catch (Exception e) {
        tracer.severe("Failure getting peer list.", e);
      }
    }

    return new DiameterIdentity[0];
  }
}
