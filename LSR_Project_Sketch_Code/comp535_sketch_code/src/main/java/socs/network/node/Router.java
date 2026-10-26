package socs.network.node;

import socs.network.message.SOSPFPacket;
import socs.network.util.Configuration;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.Vector;
import socs.network.message.LSA;
 



public class Router {

  // MessageStats type, counts the number of various message types.
  private MessageCount stats = new MessageCount();


  // ---------------------------------------------------------------------------------------
  // Packet types carried in SOSPFPacket.sospfType. 0/1/2 are defined by the starter code;
  // 3 is added for the attach request/reply so it is never mistaken for a HELLO.
  // ---------------------------------------------------------------------------------------
  // 
  static final short TYPE_HELLO = 0;     // 3-way handshake
  static final short TYPE_LSU = 1;          // linkState Update 
  static final short TYPE_APP_MESSAGE = 2;  // application message 
  static final short TYPE_ATTACH = 3;       // attach request and its reply

  // SOSPFPacket.message inside attach reply 
  static final String ATTACH_ACCEPTED = "ACCEPTED";
  static final String ATTACH_REJECTED = "REJECTED";

  // Upper bound on how long one HELLO handshake waits for the other routers (ms).
  private static final int HELLO_TIMEOUT_MS = 5000;

  protected LinkStateDatabase lsd;

  RouterDescription rd = new RouterDescription();

  // assuming that all routers are with 4 ports
  Link[] ports = new Link[4];

  // Other routers connect to this socket. It stays open until the router quits. 
  private ServerSocket serverSocket;

  // Guards ports[], reservedPorts[] 
  private final Object portLock = new Object();


   // reservedPorts[i] == true means port i is still empty but promised to an attach that is
   // waiting for a Y/N answer. Guarantees that when the answer finally is
   // "yes" there is still a free slot for the link. (We cannot simply hold portLock while
   // waiting - two routers attaching each other would then deadlock, because 
   // both side need to check port list: findLink() and that required lock)
  private final boolean[] reservedPorts = new boolean[4];

  
  // It's a lock for connection handle thread, that is currently asking Y/N question. From the
  // moment the question printed util the reply sent to the other router. Other thread wait for the 
  // lock, so we prevent the conflict.
  private final Object attachApprovalLock = new Object();

  
  // A letter box
  private volatile CompletableFuture<Boolean> pendingAttachDecision;

  // It's a HashSet that keep tracking the current attach (those haven't receive response)
  // which make sure won't have duplicate attach after the attach request been sent
  private final Set<String> outgoingAttachRouters =Collections.synchronizedSet(new HashSet<String>());

  // =======================================================================================
  // Construction / startup
  // =======================================================================================

  public Router(Configuration config) {
    rd.simulatedIPAddress = config.getString("socs.network.router.ip");
    lsd = new LinkStateDatabase(rd);

    try {
      rd.processIPAddress = InetAddress.getLocalHost().getHostAddress(); // obtain the real host IP address
      serverSocket = bindFreePort();  //can't use ServerSocket(0) here
      rd.processPortNumber = (short) serverSocket.getLocalPort();
    } catch (IOException e) {
      throw new RuntimeException("cannot start router: " + e.getMessage(), e);
    }

    System.out.println("========================================");
    System.out.println("Process IP    : " + rd.processIPAddress);
    System.out.println("Process Port  : " + rd.processPortNumber);
    System.out.println("Simulated IP  : " + rd.simulatedIPAddress);
    System.out.println("========================================");

    // Now we create an new thread for the requestHandler()
    Thread reqHandleThread = new Thread(() -> requestHandler());
    // start an new thread
    reqHandleThread.setDaemon(true);
    reqHandleThread.start();
  }


  // Method that help to find a free port on your computer and create a ServerSocket
  private static ServerSocket bindFreePort() throws IOException {
    Random random = new Random();
    for (int attempt = 0; attempt < 200; attempt++) {
      int port = 10000 + random.nextInt(Short.MAX_VALUE - 10000); // 10000 .. 32766
      try {
        return new ServerSocket(port);
      } catch (IOException portInUse) {
        // try another one
      }
    }
    throw new IOException("no free port found in range 10000-32766");
  }


  // =======================================================================================
  // Port list helpers (all access to ports[] / reservedPorts[] need these methods)
  // =======================================================================================

  /** Returns the link whose remote end has the given simulated IP, or null. */
  private Link findLink(String simulatedIP) {
    synchronized (portLock) {
      for (Link link : ports) {
        if (link != null && link.router2.simulatedIPAddress.equals(simulatedIP)) {
          return link;
        }
      }
      return null;
    }
  }

  /** Reserves the first port that is neither occupied nor reserved. Returns -1 if none. */
  private int reservePort() {
    synchronized (portLock) {
      for (int i = 0; i < ports.length; i++) {
        if (ports[i] == null && !reservedPorts[i]) {
          reservedPorts[i] = true;
          return i;
        }
      }
      return -1;
    }
  }

  /** Gives a reservation back without storing a link (attach rejected or failed). */
  private void releasePort(int port) {
    synchronized (portLock) {
      reservedPorts[port] = false;
    }
  }

  /**
   * Stores link in the reserved port and clears the reservation.
   * Returns false and stores nothing if a link to the same router already exists.
   * That happens when two routers attach each other at the same time and the other
   * direction was accepted first; keeping only the first link is what makes a simultaneous
   * attach safe. The caller must then release port from the reservation.
   */
  private boolean commitPort(int port, Link link) {
    synchronized (portLock) {
      if (!reservedPorts[port] || ports[port] != null) {
      throw new IllegalStateException("Port not reserved or it's unexpectedly occupied.");
    }
      if (findLink(link.router2.simulatedIPAddress) != null) {
        return false;
      }
      ports[port] = link;
      reservedPorts[port] = false;
      return true;
    }
  }

  /** Copy of all links, so callers can do network I/O without holding portLock.
   *  It make sure you copy the latest list, but after that the list could be updated
   */
  private List<Link> snapshotLinks() {
    synchronized (portLock) {
      List<Link> result = new ArrayList<>();
      for (Link link : ports) {
        if (link != null) {
          result.add(link); // we get it's ref
        }
      }
      return result;
    }
  }

  /**
   * Removes exactly this link object from the port table and frees its port.
   * Compares by reference (ports[i] == link)
   */
  private boolean removeLink(Link link) {
    synchronized (portLock) {
      for (int i = 0; i < ports.length; i++) {
        if (ports[i] == link) {
          ports[i] = null;
          return true;
        }
      }
      return false;
    }
  }

  /**
   * Moves the neighbour's state forward (null -> INIT -> TWO_WAY) and prints the transition msg.
   * Never moves backwards!!! so running start again on an already TWO_WAY neighbour
   * does not print any msg.
   * 
   * PA2 modification: advanceNeighborStatus return BOOLEAN !
   */
  private boolean advanceNeighborStatus(Link link, RouterStatus target) {
    boolean changed;
    synchronized (portLock) {
      RouterStatus current = link.router2.status;
      changed = current == null || current.ordinal() < target.ordinal();
      if (changed) { // check if the changed of status is legal
        link.router2.status = target;
      }
    }
    if (changed) {
      System.out.println("set " + link.router2.simulatedIPAddress + " STATE to " + target + ";");
    }
    return changed;
  }

  // =======================================================================================
  // Packet / stream helpers
  // =======================================================================================

  /** A packet of the given type with all fields filled in. */
  private SOSPFPacket newPacket(short typeMessage, String dstSimulatedIP) {
    SOSPFPacket packet = new SOSPFPacket();
    packet.sospfType = typeMessage;
    packet.srcProcessIP = rd.processIPAddress;
    packet.srcProcessPort = rd.processPortNumber;
    packet.srcIP = rd.simulatedIPAddress;
    packet.dstIP = dstSimulatedIP;
    packet.routerID = rd.simulatedIPAddress; // ? not sure
    packet.neighborID = rd.simulatedIPAddress; // sender's simulated IP 
    return packet;
  }

  /**
   * Opens the object output stream and pushes its header to the peer right away.
   * IMPORTANT : An ObjectInputStream constructor blocks until it has
   * read the header written by the peer's ObjectOutputStream; if both sides opened their
   * input stream first they would wait for each other forever.
   */
  private static ObjectOutputStream openOutput(Socket socket) throws IOException {
    ObjectOutputStream out = new ObjectOutputStream(socket.getOutputStream());
    out.flush();
    return out;
  }

  private static Socket openSocket(String processIP, short processPort) throws IOException {
    Socket socket = new Socket();
    try {
      // EDGE CASE: bound connection setup and reads, including the stream header.
      // Setting SO_TIMEOUT after constructing ObjectInputStream is too late.
      socket.connect(new InetSocketAddress(processIP, processPort), HELLO_TIMEOUT_MS);
      socket.setSoTimeout(HELLO_TIMEOUT_MS);
      return socket;
    } catch (IOException | IllegalArgumentException e) {
      socket.close();
      throw e;
    }
  }

  /** Reads one SOSPFPacket; anything else on the wire is treated as an I/O error. */
  private static SOSPFPacket readPacket(ObjectInputStream in)
      throws IOException, ClassNotFoundException {
    Object received = in.readObject();
    if (!(received instanceof SOSPFPacket)) {
      throw new IOException("unexpected object on the wire: "
          + (received == null ? "null" : received.getClass().getName()));
    }
    return (SOSPFPacket) received;
  }

  //---------------------------PA2 new helper method--------------------

  private boolean isTwoWay(Link link) {
    synchronized (portLock) {
      // EDGE CASE: router1 is shared local rd; HELLO only advances router2.status
      // for this neighbor. Checking router1 would reject every valid link.
      return link != null && link.router2.status == RouterStatus.TWO_WAY;
    }
  }

  private int getPort(Link link) {
    synchronized (portLock) {
      for (int i=0; i<ports.length; i++) {
        if (ports[i] == link) {
          return i;
        }
      }
      return -1;
    }
  }

  private boolean addNeighborToLSA(Link link) {
    synchronized (portLock) {
      int port = getPort(link);
      // EDGE CASE: port 0 is valid; -1 means the link is no longer installed.
      // Keep the checks and insertion together during concurrent start commands.
      if (isTwoWay(link) && port >= 0) {
        return lsd.addLinkToLSA(link.router2.simulatedIPAddress, port, link.weight);
      }
      return false;
    }
  }


  /**
   * Send a packet to neighbor (we have it's routerDescription inside the link)
   * Don't need to wait for peer's reply
   */
  private boolean sendOneWayMessage(RouterDescription neighbor, SOSPFPacket packet) {
    try (Socket socket = openSocket(neighbor.processIPAddress, neighbor.processPortNumber);
         ObjectOutputStream out = openOutput(socket);
         ObjectInputStream peerInput = new ObjectInputStream(socket.getInputStream())) {
      // Reading the peer's stream header ensures it opened its output before we
      // close. No packet reply is expected; try-with-resources closes both streams.
      out.writeObject(packet);
      out.flush();
      stats.countSent(packet.sospfType);
      return true;
    } catch (IOException e) {
      System.out.println("Cannot send to " + neighbor.simulatedIPAddress + ": " + e.getMessage());
      return false;
    }
  }

  /**
   * Send the LSA update to all neighbors with status TWO_WAY except the 
   * exception IP (this happen when you retransmit neighbor's message
   * you don't need to send it back to it again)
   */
  private void broadcastLSAUpdate(Vector<LSA> LSAs, String exception) {
    for (Link link : snapshotLinks()) {
      RouterDescription neighbor = link.router2;
      // EDGE CASE: locally originated broadcasts have exception == null.
      if (!isTwoWay(link) || neighbor.simulatedIPAddress.equals(exception)) {
        continue;
      }
      SOSPFPacket packet = newPacket(TYPE_LSU, neighbor.simulatedIPAddress);
      packet.lsaArray = LSAs;
      sendOneWayMessage(neighbor, packet);
    }
  }

  /**
   * When own router received the LSA update
   * Check if there is any real update for LSA (new seq number or LSA
   * that haven't exist inside the _store)
   */
  private void receiveLSAUpdate(SOSPFPacket packet) {
    List<LSA> updates = this.lsd.merge(packet.lsaArray);
    if (!updates.isEmpty()) {
      broadcastLSAUpdate(new Vector<>(updates), packet.srcIP);
    }
  }


  /**
   *  Find the next Link for reaching the destination IP, according to the shotest path
   * return null if no path or all no TWO_WAY neighbor.
   * IF the destination IP is own IP?
   */

  private Link nextHopLink(String destinationIP) {
    Path path = lsd.findShortestPathTo(destinationIP);
    // EDGE CASE: an unreachable destination has no Path; a path to this router
    // exists but has no next hop. Neither should dereference null.
    if (path == null || path.nextHop() == null) {
      return null;
    }
    Link link = findLink(path.nextHop());
    if (link == null || !isTwoWay(link)) {
      return null;
    }
    return link;
  }

  // -------------------------------------------------------------------------------------



  // =======================================================================================
  // Incoming connections
  // =======================================================================================

  /**
   * Listener loop: accept a connection and hand it to a fresh thread, so that a request
   * that takes a long time (e.g. waiting for the user's Y/N) never blocks other routers.
   */
  private void requestHandler() {
    while (!serverSocket.isClosed()) {
      try {
        Socket socket = serverSocket.accept();
        Thread handler = new Thread(() -> connectionHandler(socket));
        handler.setDaemon(true);
        handler.start();
      } catch (IOException e) {
        if (serverSocket.isClosed()) {
          return; // quit was called
        }
        System.out.println("Accept failed: " + e.getMessage());
      }
    }
  }

  /** Reads the first packet of a connection and dispatches on its type. */
  private void connectionHandler(Socket socket) {
    try (Socket s = socket) {
      s.setSoTimeout(HELLO_TIMEOUT_MS);
      try (ObjectOutputStream out = openOutput(s);
           ObjectInputStream in = new ObjectInputStream(s.getInputStream())) {

        SOSPFPacket packet = readPacket(in);
        stats.countReceived(packet.sospfType);
        switch (packet.sospfType) {
          case TYPE_ATTACH:
            handleAttachRequest(packet, out);
            break;
          case TYPE_HELLO:
            handleHello(packet, s, in, out);
            break;
          case TYPE_LSU:
            receiveLSAUpdate(packet); // step1: merge update step2: broadcast update
            break;
          case TYPE_APP_MESSAGE:
            handleApplicationMessage(packet);
            break;
          default:
            System.out.println("received unsupported packet type " + packet.sospfType
                + " from " + packet.srcIP + ";");
        }
      }
    } catch (IOException | ClassNotFoundException e) {
      System.out.println("connection error: " + e.getMessage());
    }
  }

  // =======================================================================================
  // Attach
  // =======================================================================================

  /**
   * attach the link to the remote router, which is identified by the given simulated ip;
   * to establish the connection via socket, you need to indentify the process IP and process Port;
   * additionally, weight is the cost to transmitting data through the link
   * <p/>
   * NOTE: this command should not trigger link database synchronization
   */

  private void processAttach(String processIP, short processPort,
                             String simulatedIP, short weight) {
    // EDGE CASE: Dijkstra needs nonnegative weights. Validate before reserving
    // a port or sending a request; zero-cost links remain valid.
    if (weight < 0 || processPort <= 0) {
      System.out.println("Cannot attach: weight must be nonnegative and process port positive.");
      return;
    }
    if (rd.simulatedIPAddress.equals(simulatedIP)) {
      System.out.println("Cannot attach to yourself.");
      return;
    }
    if (findLink(simulatedIP) != null) {
      System.out.println("Cannot attach: a link to " + simulatedIP + " already exists.");
      return;
    }
    // check if we have already sent the attach request
    if (!outgoingAttachRouters.add(simulatedIP)) {// fail to add it
      System.out.println("An attach request to " + simulatedIP + " is already pending.");
      return;
    }

    int port = -1;
    boolean committed = false;
    try {
      port = reservePort();
      if (port == -1) {
        System.out.println("Cannot attach: all ports are in use.");
        return;
      }

      try (Socket socket = openSocket(processIP, processPort);
           ObjectOutputStream out = openOutput(socket);
           ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {

        SOSPFPacket request = newPacket(TYPE_ATTACH, simulatedIP);
        request.weight = weight;
        out.writeObject(request);
        out.flush();  // after we send the message we need to count number of TYPE_ATTACH it sent
        stats.countSent(TYPE_ATTACH);  // count the TYPE_ATTACH


        // EDGE CASE: human approval can take longer than HELLO_TIMEOUT_MS.
        // Connection/header reads are bounded, but the Y/N wait is deliberate.
        socket.setSoTimeout(0);

        // Blocks until the remote user has typed Y or N.
        SOSPFPacket reply = readPacket(in);
        stats.countReceived(reply.sospfType);
        if (reply.sospfType != TYPE_ATTACH || !simulatedIP.equals(reply.srcIP)
            || !rd.simulatedIPAddress.equals(reply.dstIP)) {
          System.out.println("Attach failed: unexpected reply.");
          return;
        }

        if (ATTACH_ACCEPTED.equals(reply.message)) {
          RouterDescription remote = new RouterDescription();
          remote.processIPAddress = processIP;
          remote.processPortNumber = processPort;
          remote.simulatedIPAddress = simulatedIP;
          remote.status = null; // attached, but no HELLO exchanged yet
          committed = commitPort(port, new Link(rd, remote, weight)); // add link from reserve ports to ports
          System.out.println("Your attach request has been accepted;");
        } else if (findLink(simulatedIP) != null) {
          // Simultaneous attach: the peer rejected our request, but we accepted theirs.
          System.out.println("Your attach request has been rejected;"
              + " (a link to " + simulatedIP + " already exists from the other direction)");
        } else { 
          System.out.println("Your attach request has been rejected;");
        }
      }
    } catch (IOException | ClassNotFoundException e) {
      System.out.println("Attach failed: " + e.getMessage());
    } finally {
      if (port != -1 && !committed) {
        releasePort(port); // release the reserved slot
      }
      outgoingAttachRouters.remove(simulatedIP);
    }
  }

  /**
   * process request from the remote router.
   * For example: when router2 tries to attach router1. Router1 can decide whether it will accept this request.
   * Runs on a connection handler thread. Asks the user through askUserYesNo()
   * stores the link on "Y", and always sends exactly one reply to the requester.
   */
  private void handleAttachRequest(SOSPFPacket request, ObjectOutputStream out) throws IOException {
    // initialise an new packet and let the msg = rejected
    SOSPFPacket reply = newPacket(TYPE_ATTACH, request.srcIP);
    reply.message = ATTACH_REJECTED;

    // Only one request ask the user at a time. The lock is held until the reply has
    // been sent, so a second request can never pick up the answer before the first one.
    synchronized (attachApprovalLock) {
      int port = -1;
      boolean committed = false;
      try {
        if (!rd.simulatedIPAddress.equals(request.dstIP)) {
          // The requester typed the wrong simulated IP for this process.
          System.out.println("received attach request from " + request.srcIP
              + " addressed to " + request.dstIP + " (not me); rejected automatically;");

        } else if (request.weight < 0) {
          // EDGE CASE: validate incoming requests as well as local commands.
          System.out.println("Attach rejected: negative link weight.");
        } else if (findLink(request.srcIP) != null) {
          // Simultaneous attach: the other direction was already accepted. The link exists,
          // so just confirm it instead of asking the user again.
          System.out.println("received HELLO from " + request.srcIP
              + "; a link already exists, accepted automatically;");
          reply.message = ATTACH_ACCEPTED;

        } else if ((port = reservePort()) == -1) { // there no free port
          System.out.println("received HELLO from " + request.srcIP
              + "; all ports are in use, rejected automatically;");

        } else {
          System.out.println("received HELLO from " + request.srcIP + ";");

          if (askUserYesNo()) {
            RouterDescription remote = new RouterDescription();
            remote.processIPAddress = request.srcProcessIP;
            remote.processPortNumber = request.srcProcessPort;
            remote.simulatedIPAddress = request.srcIP;
            remote.status = null; // attached, but no HELLO exchanged yet
            committed = commitPort(port, new Link(rd, remote, request.weight));
            reply.message = ATTACH_ACCEPTED;
            System.out.println("You accepted the attach request;");
          } else {
            System.out.println("You rejected the attach request;");
          }
        }
      } finally {
        if (port != -1 && !committed) {
          releasePort(port);
        }
      }

      out.writeObject(reply);
      out.flush();
      stats.countSent(TYPE_ATTACH);// when you reply the attach req, add to the stats
    }
  }

  /**
   * Publishes a decision box for the terminal thread and blocks until the user has typed
   * Y or N. Must be called while holding attachApprovalLock.
   */
  private boolean askUserYesNo() {
    CompletableFuture<Boolean> decision = new CompletableFuture<>();
    pendingAttachDecision = decision;
    // EDGE CASE: publish the answer box BEFORE printing the question; otherwise
    // a fast Y/N may be parsed as a command and leave the requester waiting.
    System.out.println("Do you accept this request? (Y/N)");
    // it could be checked by other thread. At each iteration of the terminal thread
    // it would check if they receive this box.  Once they recieve they need to handle the request
    // and give an response
    try {
      return decision.get();
    } catch (InterruptedException | ExecutionException e) {
      return false;
    }
  }




  // =======================================================================================
  // start (HELLO handshake)
  // =======================================================================================

  // 这个部分三个方法都在PA2中进行修改

  /**
   * broadcast Hello to neighbors
   * <p/>
   * Performs the three-message HELLO handshake with every attached router. Each handshake
   * is short and needs no user input, so this runs directly on the terminal thread.
   */

  /**
   *  process start will do the 3-way handshake with all it's neighbors
   *  for those neighbors who haven't do the 3-way handshake (we add this
   * new connection to our lsa and also ask neighbor to add this link to theirs LSA)
   */

  // HELLO establishes neighbors; each start also exchanges the current full LSDB.
  private void processStart() {
    List<Link> links = snapshotLinks();
    if (links.isEmpty()) {
      System.out.println("No attached routers; run attach first.");
      return;
    }
    for (Link link : links) {
      helloHandshake(link);
    }
    // EDGE CASE: repeated start and joining existing networks must exchange the
    // full LSDB even if this router's own LSA did not change.
    broadcastLSAUpdate(lsd.deepCopy(), null);

    if (!outgoingAttachRouters.isEmpty()) {
      System.out.println("Note: some attach requests are still waiting for an answer;"
          + " run start again once they are accepted.");
    }
  }

  /**
   * Initiator side of the handshake 
   * send HELLO, receive HELLO (neighbour becomes TWO_WAY here), send HELLO again.
   */

  // 这里不用写广播，因为helloHandshake只能在processStart中被调用
  private boolean helloHandshake(Link link) {
    boolean result = false;

    RouterDescription neighbor = link.router2;
    try (Socket socket = openSocket(neighbor.processIPAddress, neighbor.processPortNumber);
         ObjectOutputStream out = openOutput(socket);
         ObjectInputStream in = new ObjectInputStream(socket.getInputStream())) {
      socket.setSoTimeout(HELLO_TIMEOUT_MS); // setup the terminate time

      // 1st HELLO
      out.writeObject(newPacket(TYPE_HELLO, neighbor.simulatedIPAddress));
      out.flush();
      stats.countSent(TYPE_HELLO); // <------- +1

      // 2nd HELLO (the neighbour's answer)
      SOSPFPacket reply = readPacket(in); 
      stats.countReceived(reply.sospfType);
      if (reply.sospfType != TYPE_HELLO || !neighbor.simulatedIPAddress.equals(reply.srcIP)
          || !rd.simulatedIPAddress.equals(reply.dstIP)) {
        System.out.println("start: unexpected reply from " + neighbor.simulatedIPAddress + ";");
        return result;
      }
      System.out.println("received HELLO from " + reply.srcIP + ";");
      // 3rd HELLO, so the neighbour also reaches TWO_WAY
      out.writeObject(newPacket(TYPE_HELLO, neighbor.simulatedIPAddress));
      out.flush();
      stats.countSent(TYPE_HELLO);  // <----- +1
      advanceNeighborStatus(link, RouterStatus.TWO_WAY);
      // EDGE CASE: both ends may start simultaneously. Always try the idempotent
      // insertion, regardless of which thread advanced this neighbor's status.
      result = addNeighborToLSA(link);
      return result;

    } catch (ConnectException e) {
      // "Connection refused": no process is listening on the neighbour's port any more, so
      // it has quit. Drop the dead link so the port is freed 
      // Other errors (eg: timeout) keep the link, the neighbour may only be slow.
      if (removeLink(link)) {
        System.out.println("start: " + neighbor.simulatedIPAddress
            + " is unreachable (connection refused); link removed;");
      }
      return result;
    } catch (IOException | ClassNotFoundException e) {
      System.out.println("start: cannot reach " + neighbor.simulatedIPAddress
          + ": " + e.getMessage());
      return result;
    }
  }

  /**
   * Responder side of the handshake, running on a connection-handler thread:
   * got HELLO (neighbour becomes INIT), send HELLO, wait for the final HELLO
   * (neighbour becomes TWO_WAY).
   */
  private void handleHello(SOSPFPacket first, Socket socket,
                           ObjectInputStream in, ObjectOutputStream out)
      throws IOException, ClassNotFoundException {
    Link link = findLink(first.srcIP); // check if the connection exist
    if (link == null || !rd.simulatedIPAddress.equals(first.dstIP)) {
      System.out.println("received HELLO from " + first.srcIP + " but it is not attached; ignored;");
      return;
    }

    System.out.println("received HELLO from " + first.srcIP + ";");
    advanceNeighborStatus(link, RouterStatus.INIT); // before attach it's null, at the first time it receive Hello it become INIT 

    out.writeObject(newPacket(TYPE_HELLO, first.srcIP));
    out.flush();
    stats.countSent(TYPE_HELLO); // <------------ +1

    socket.setSoTimeout(HELLO_TIMEOUT_MS);
    SOSPFPacket third = readPacket(in);
    stats.countReceived(third.sospfType);
    if (third.sospfType != TYPE_HELLO || !first.srcIP.equals(third.srcIP)
        || !rd.simulatedIPAddress.equals(third.dstIP)) {
      System.out.println("start: unexpected packet from " + first.srcIP + ";");
      return;
    }
    System.out.println("received HELLO from " + third.srcIP + ";");
    advanceNeighborStatus(link, RouterStatus.TWO_WAY);
    if (addNeighborToLSA(link)) {
      broadcastLSAUpdate(lsd.deepCopy(), null);
    } else {
      // EDGE CASE: repeated HELLO still exchanges the full database. Equal
      // sequence numbers stop these snapshots from flooding indefinitely.
      SOSPFPacket update = newPacket(TYPE_LSU, link.router2.simulatedIPAddress);
      update.lsaArray = lsd.deepCopy();
      sendOneWayMessage(link.router2, update);
    }
  }

  // =======================================================================================
  // neighbors / quit
  // =======================================================================================

  /**
   * output the neighbors of the routers
   */
  private void processNeighbors() {
    List<String> neighbors = new ArrayList<>();
    synchronized (portLock) {
      for (Link link : ports) {
        if (link != null && link.router2.status == RouterStatus.TWO_WAY) {
          neighbors.add(link.router2.simulatedIPAddress);
        }
      }
    }
    for (String ip : neighbors) {
      System.out.println(ip);
    }
  }

  /**
   * disconnect with all neighbors and quit the program
   */
  private void processQuit() {
    try {
      serverSocket.close();
    } catch (IOException ignored) {
      // nothing useful to do while exiting
    }
    System.exit(0);
  }

  // =======================================================================================
  // PA2: detect computes a path from the current LSDB.
  // =======================================================================================

  /**
   * output the shortest path to the given destination ip
   * <p/>
   * format: source ip address  -> ip address -> ... -> destination ip
   *
   * @param destinationIP the ip adderss of the destination simulated router
   */
  private void processDetect(String destinationIP) {
    String path = lsd.getShortestPath(destinationIP);
    if (path == null) {
      System.out.println("Path not found");
    } else {
      System.out.println("Path found: " + path);
    }
  }

  /**
   * disconnect with the router identified by the given destination ip address
   * Notice: this command should trigger the synchronization of database
   *
   * @param portNumber the port number which the link attaches at
   */
  // Dynamic-topology placeholder; not listed among the PA2 requirements.
  private void processDisconnect(short portNumber) {

  }

  /**
   * attach the link to the remote router, which is identified by the given simulated ip;
   * to establish the connection via socket, you need to indentify the process IP and process Port;
   * additionally, weight is the cost to transmitting data through the link
   * <p/>
   * This command does trigger the link database synchronization
   */
  private void processConnect(String processIP, short processPort,
                              String simulatedIP, short weight) {

  }

  /**
   * update the weight of an attached link
   */
  private void updateWeight(String processIP, short processPort,
                            String simulatedIP, short weight) {

  }

  /**
   * update the weight of a specific port.
   * This change should trigger synchronization of the Link State Database by sending
   * a Link State Advertisement (LSA) update to all neighboring routers in the topology.
   *
   * @param portNumber the port number (0-3) to update
   * @param newWeight the new weight/cost for the link attached to this port
   */
  private void processUpdate(short portNumber, short newWeight) {

  }

  /**
   * send an application-level message from this router to the destination router.
   * The message must be forwarded hop-by-hop according to the current shortest path.
   * <p/>
   * When you run send, the window of the router where you run the command should print:
   * "Sending message to <Destination IP>"
   * <p/>
   * For each intermediate router on the shortest path (excluding the source and destination),
   * the router window should print:
   * "Forwarding message from <Source IP> to <Destination IP>"
   * <p/>
   * When the destination router receives the message, the router window should print:
   * "Received message from <Source IP>;"
   * "Message: <Message>"
   *
   * @param destinationIP the simulated IP address of the destination router
   * @param message the message content to send
   */ // <-------------------------------------PA2
  private void processSend(String destinationIP, String message) {
    if (destinationIP.equals(rd.simulatedIPAddress)) {
      // EDGE CASE: local delivery needs no socket or network packet count.
      System.out.println("Sending message to " + destinationIP);
      System.out.println("Received message from " + rd.simulatedIPAddress + ";");
      System.out.println("Message: " + message);
      return;
    }
    Link link = nextHopLink(destinationIP);
    if (link  == null) {
      System.out.println("Path not found");
      return;
    }
    SOSPFPacket packet = newPacket(TYPE_APP_MESSAGE, destinationIP);
    packet.message = message;
    System.out.println("Sending message to " + destinationIP);
    sendOneWayMessage(link.router2, packet);
  }

  /**
   * handle incoming application message packet.
   * This method should be called when a router receives a SOSPFPacket with sospfType = 2 (Application Message).
   * <p/>
   * If this router is the destination (packet.dstIP equals this router's simulatedIPAddress):
   * - Print "Received message from <Source IP>;"
   * - Print "Message: <Message>"
   * <p/>
   * If this router is an intermediate router:
   * - Print "Forwarding message from <Source IP> to <Destination IP>"
   * - Forward the packet to the next hop on the shortest path to the destination
   * - Do NOT print or inspect the message payload
   *
   * @param packet the received application message packet
   */ // <-------------------------- PA2
  private void handleApplicationMessage(socs.network.message.SOSPFPacket packet) {
    // router is the destination
    if (packet.dstIP.equals(rd.simulatedIPAddress)) {
      System.out.println("Received message from " + packet.srcIP + ";");
      System.out.println("Message: " + packet.message);
      return;
    }

    // If the router not the destination 
    Link nextLink = nextHopLink(packet.dstIP);
    if (nextLink == null) {
      System.out.println("Path not found");
      return;
    }
    System.out.println("Forwarding message from " + packet.srcIP + " to " + packet.dstIP);
    System.out.println("Next hop: " + nextLink.router2.simulatedIPAddress);
    // EDGE CASE: across multiple hops, preserve the original source and payload.
    // The intermediate router only uses dstIP to choose its next link.
    sendOneWayMessage(nextLink.router2, packet);
  }

  // =======================================================================================
  // Terminal
  // =======================================================================================

  /**
   * Command loop of the terminal thread. Every iteration reads one line; if a remote attach
   * request is waiting for an answer, that line is the Y/N answer, otherwise it is a command.
   */
  public void terminal() {
    BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
    try {
      while (true) {
        System.out.print(">> ");
        String command = br.readLine();
        if (command == null) {
          processQuit(); // end of input (Ctrl-D / Ctrl-Z): behave like quit
          return;
        }
        command = command.trim();
        if (command.isEmpty()) {
          continue;
        }

        // The line answers a pending attach request, not a command.
        CompletableFuture<Boolean> decision = pendingAttachDecision;
        if (decision != null) {
          if (command.equalsIgnoreCase("Y")) {
            // Clear the box BEFORE completing it: the waiting handler wakes up on complete()
            // and may immediately publish the next question, which must not be wiped out.
            pendingAttachDecision = null;
            decision.complete(true);
          } else if (command.equalsIgnoreCase("N")) {
            pendingAttachDecision = null;
            decision.complete(false);
          } else {
            System.out.println("Please enter Y or N.");
          }
          continue;
        }

        try {
          runCommand(command);
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
          System.out.println("Invalid arguments: " + command);
        }
      }
    } catch (IOException e) {
      e.printStackTrace();
    }
  }

  private boolean hasArgumentCount(String[] arguments, int expected, String usage) {
    if (arguments.length == expected) {
      return true;
    }
    System.out.println("Usage: " + usage);
    return false;
  }

  /** Parses one command line and runs the matching process* method. */
  private void runCommand(String command) {
    command = command.trim();
    if (command.isEmpty()) {
      return;
    }
    // EDGE CASE: consecutive spaces or tabs are valid command separators.
    // Check argument counts before indexing; an incomplete command must not crash.
    String[] arguments = command.split("\\s+");
    switch (arguments[0]) {
      case "detect":
        if (hasArgumentCount(arguments, 2, "detect [Destination IP]")) {
          processDetect(arguments[1]);
        }
        break;
      case "disconnect":
        if (hasArgumentCount(arguments, 2, "disconnect [Port Number]")) {
          processDisconnect(Short.parseShort(arguments[1]));
        }
        break;
      case "quit":
        if (hasArgumentCount(arguments, 1, "quit")) {
          processQuit();
        }
        break;
      case "stats":
        if (hasArgumentCount(arguments, 1, "stats")) {
          System.out.println(stats.reportMessageCount());
        }
        break;
      case "attach":
        if (hasArgumentCount(arguments, 5,
            "attach [Process IP] [Process Port] [Simulated IP] [Link Weight]")) {
          String processIP = arguments[1];
          short processPort = Short.parseShort(arguments[2]);
          String simulatedIP = arguments[3];
          short weight = Short.parseShort(arguments[4]);
          // Human approval must not block this terminal from handling incoming Y/N.
          Thread attach = new Thread(
              () -> processAttach(processIP, processPort, simulatedIP, weight),
              "attach-" + simulatedIP);
          attach.setDaemon(true);
          attach.start();
        }
        break;
      case "start":
        if (hasArgumentCount(arguments, 1, "start")) {
          processStart();
        }
        break;
      case "lsd":
        if (hasArgumentCount(arguments, 1, "lsd")) {
          System.out.println(lsd);
        }
        break;
      case "connect":
        if (hasArgumentCount(arguments, 5,
            "connect [Process IP] [Process Port] [Simulated IP] [Link Weight]")) {
          processConnect(arguments[1], Short.parseShort(arguments[2]),
              arguments[3], Short.parseShort(arguments[4]));
        }
        break;
      case "neighbors":
        if (hasArgumentCount(arguments, 1, "neighbors")) {
          processNeighbors();
        }
        break;
      case "send":
        // EDGE CASE: tokenize only the command and destination; preserve spaces
        // and tabs inside the application payload rather than rebuilding the text.
        String[] messageArguments = command.split("\\s+", 3);
        if (hasArgumentCount(messageArguments, 3, "send [Destination IP] [Message]")) {
          processSend(messageArguments[1], messageArguments[2]);
        }
        break;
      case "update":
        if (hasArgumentCount(arguments, 3, "update [Port Number] [New Weight]")) {
          processUpdate(Short.parseShort(arguments[1]), Short.parseShort(arguments[2]));
        }
        break;
      default:
        System.out.println("Unknown command: " + command);
    }
  }
}
