PA1 ROUTER IMPLEMENTATION NOTES
========================================================================

1. RUNNING INSTRUCTIONS
------------------------------------------------------------------------
All commands are for Windows PowerShell.

Step 1 - Open PowerShell and go to the project folder:

    cd <path-to>\comp535_sketch_code

Step 2 - Create a lib folder for the dependency:

    mkdir lib

Step 3 - Download the dependency into the lib folder:

    Invoke-WebRequest -Uri https://repo1.maven.org/maven2/com/typesafe/config/1.3.1/config-1.3.1.jar -OutFile lib\config-1.3.1.jar

Steps 2 and 3 only need to be done once.

Step 4 - Collect all .java source files:

    $srcs = Get-ChildItem -Recurse src\main\java -Filter *.java | ForEach-Object { $_.FullName }

This step prints nothing. That is expected.

Step 5 - Compile:

    javac -encoding UTF-8 -d out -cp lib\config-1.3.1.jar $srcs

If there are no errors, the compilation succeeded. Warnings such as
"source value 8 is obsolete" can be ignored.

Step 6 - Start the first router:

    java -cp "out;lib\config-1.3.1.jar" socs.network.Main conf\router1.conf

You should see:

    ========================================
    Process IP    : 192.168.0.50
    Process Port  : 27892
    Simulated IP  : 192.168.1.1
    ========================================
    >>

Step 7 - Open more PowerShell windows for the other routers. In each new
window, first go to the project folder:

    cd <path-to>\comp535_sketch_code

Then start a router. Only change the number at the end:

    java -cp "out;lib\config-1.3.1.jar" socs.network.Main conf\router2.conf
    java -cp "out;lib\config-1.3.1.jar" socs.network.Main conf\router3.conf

Each window must use a different configuration file (router1.conf to
router7.conf). New windows do not need to repeat Steps 2 to 5; compiling
once is enough. Use the destination router's printed Process IP, Process
Port, and Simulated IP in an attach command.

After changing the code: close all router windows, run Step 4 and Step 5
again in one window, then start the routers again with Steps 6 and 7.

Step 8 - PA1 commands:

    attach [Process IP] [Process Port] [Simulated IP] [Link Weight]
    start
    neighbors

Attach: the receiver answers Y or N. Acceptance stores a Link at both ends,
but the neighbor is not TWO_WAY yet. Rejection leaves no new Link.

Start: exchanges three HELLO messages. The receiver moves from INIT to
TWO_WAY; the starter reaches TWO_WAY after receiving the second HELLO.

Neighbors: prints one Simulated IP per line, only for links in TWO_WAY state.

========================================================================

2. FOUR TYPES OF THREADS
------------------------------------------------------------------------
1) Terminal thread - the only thread that reads System.in. It runs most
   commands and interprets the next input as Y/N when approval is pending.

2) Listener thread - requestHandler() waits on ServerSocket.accept() and
   gives each accepted socket to a connection-handler thread. Waiting for
   one user's answer does not stop the listener from accepting connections.

3) Connection-handler threads - connectionHandler() reads the first packet,
   dispatches an attach or HELLO exchange by packet type, then closes that
   socket when the exchange ends.

4) Outgoing attach threads - processAttach() waits for the remote Y/N answer
   on its own thread. The terminal remains free to answer an incoming attach,
   including when two routers try to attach to each other at the same time.

========================================================================

3. ORDINARY METHODS: WHAT EACH GROUP DOES
------------------------------------------------------------------------
Startup and packets

* Router() reads the configured Simulated IP, opens the listening socket,
  prints router identifiers, and starts the listener thread.
* bindFreePort() tries up to 200 ports in 10000-32766. The starter code
  stores a Process Port in a Java short; ServerSocket(0) could select a
  port above 32767 that cannot be stored as a positive short.
* newPacket() fills sender/destination fields. Packet type 3 means attach;
  type 0 means HELLO. readPacket() checks that the received object is a
  SOSPFPacket. openOutput() flushes the output-stream header before opening
  ObjectInputStream so the two sides do not wait for each other's header.

Link slots and status

* findLink() checks for an existing Link to a Simulated IP.
* reservePort() marks an empty slot; releasePort() frees a failed or
  rejected reservation; commitPort() stores an accepted Link and clears
  its reservation. A duplicate Link is not committed.
* snapshotLinks() copies the current links before start performs network
  I/O. removeLink() frees the slot holding a particular Link object.
* advanceNeighborStatus() moves a neighbor toward INIT or TWO_WAY without
  moving it backward. processNeighbors() prints only TWO_WAY links.

Attach, start, and input

* processAttach() rejects self-attachment or an existing/pending duplicate,
  reserves a slot, sends the weight in an attach packet, then commits or
  releases the slot after the reply.
* handleAttachRequest() checks the addressed Simulated IP and slot capacity,
  asks the user when needed, and sends ACCEPTED or REJECTED. askUserYesNo()
  passes the Y/N answer from terminal() to the waiting handler.
* processStart() takes a link snapshot. helloHandshake() sends, receives,
  and sends the three HELLO messages. handleHello() handles the receiving
  side and moves its neighbor through INIT to TWO_WAY.
* terminal() reads input; runCommand() chooses the command handler.
  processQuit() closes the listener and exits.

========================================================================

4. WHY A PENDING ATTACH RESERVES A PORT
------------------------------------------------------------------------
A sends attach to B and waits for B's answer. During that wait, another
request could choose the same empty slot. reservedPorts marks the slot
before waiting so the second request cannot take it. If the request fails
or B answers N, releasePort() clears the mark. If B answers Y, commitPort()
stores the Link in ports[] and clears the mark. The slot is then occupied,
not still reserved.

Why not temporarily put a Link in ports[]? start reads ports[] to choose
HELLO destinations. A pending Link there could make start try to handshake
before approval. It could also make a simultaneous reverse attach look
like an existing connection before the user has answered.

portLock protects ports[] and reservedPorts[]. snapshotLinks() lets start
use the latest copy without holding portLock while doing network I/O.
Each router has four slots; a fifth request is rejected when none is free.

========================================================================

5. SIMULTANEOUS AND DUPLICATE ATTACH REQUESTS
------------------------------------------------------------------------
A and B may each send attach to the other before either answer arrives.
Separate outgoing threads leave both terminal threads free to answer.
commitPort() keeps at most one local Link to the same Simulated IP; a
second commit returns false and the unused reservation is released.

* Both directions accepted: each router ends with one Link, not two.
* One direction accepted, the other rejected: the accepted direction can
  still establish the Link.
* Both directions rejected: neither establishes a Link.

Depending on timing, an incoming request may find an existing Link and be
accepted automatically rather than asking the user again. The
outgoingAttachRouters set blocks a duplicate outgoing attach to the same
Simulated IP while the first is pending; findLink() blocks a later attach
when the Link already exists.

========================================================================

6. APPROVAL INPUT AND OTHER EDGE CASES
------------------------------------------------------------------------
attachApprovalLock lets only one incoming handler ask for Y/N at a time.
pendingAttachDecision is a CompletableFuture<Boolean> used as a mailbox:
the handler waits; terminal() completes it after Y or N. Other input
prints a reminder and leaves the same question pending.

An attach addressed to the wrong Simulated IP is rejected automatically.
Empty terminal lines are ignored. Malformed numeric arguments print an
error; unknown commands print a message without ending the terminal.

start with no links prints a message. If an outgoing attach is still
pending, start notes that it may need to be run again after acceptance.
A missing or invalid second HELLO prevents the starter from reaching
TWO_WAY. The starter changes to TWO_WAY before sending the final HELLO;
if that last delivery fails, the two ends may temporarily disagree.

========================================================================

7. CONNECTION LIFETIME AND QUIT
------------------------------------------------------------------------
Each attach or HELLO exchange uses a new TCP socket. After attach, a Link
remembers the peer's Process IP and Process Port for later exchanges.

processQuit() closes this router's listening socket and exits. It does
not instantly notify the other routers. A peer may still list this Link
until it runs start again. If that attempt gets Connection refused,
helloHandshake() removes the Link; the next neighbors call omits it.
Other I/O failures do not necessarily remove the Link. PA1 has no
background failure detector.

========================================================================

8. PA1 SCOPE AND AI ASSISTANCE
------------------------------------------------------------------------
PA1 implements attach, the HELLO part of start, and neighbors. Link-state
database synchronization and the other routing commands belong to later
assignments and are not implemented here.

AI assistance used in this work:

1) We consulted generative AI to understand why openOutput() flushes the
   ObjectOutputStream header before creating ObjectInputStream. We checked
   that explanation against the Java code and its socket setup order.

2) We consulted generative AI about process-port selection. Because the
   starter code stores the port in a Java short, we checked that
   bindFreePort() chooses a port in the supported 10000-32766 range.

3) We used generative AI to help draft and organize this README. We edited
   the final wording and checked the build commands, method descriptions,
   and PA1 scope against the submitted code.

4) We used generative AI as a review aid to identify possible edge cases,
   including simultaneous or duplicate attach requests, invalid Y/N input,
   and exhausted link slots. We inspected the relevant code paths and
   tested these cases; AI suggestions were not treated as proof of correctness.