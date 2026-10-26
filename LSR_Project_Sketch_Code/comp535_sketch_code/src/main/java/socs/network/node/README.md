# PA2 Implementation and Running Notes

This README describes our implementation of PA2. Run all commands below from the Java project directory containing `pom.xml`, `src/`, and `conf/`.

## Collaboration Statement

Danny Chou and Rui Zhang each independently implemented all parts of the code. Danny Chou was then responsible for debugging the code and checking edge cases, and Rui Zhang was responsible for writing this README. We both carried out the final testing of the program together.

## Requirements

- JDK 8 or later and Maven 3 or later.
- Run one Java process per router, using a different configuration file in each terminal.
- Rebuild after downloading or changing source code. Generated `target/` and `out/` files are not versioned.

## Build

From the directory containing `pom.xml`:

```text
mvn clean package assembly:single
```

This produces `target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar`.

## Start five routers

Open five terminals in the Java project directory. Run one command per terminal:

```text
java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router1.conf
java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router2.conf
java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router3.conf
java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router4.conf
java -jar target/COMP535-1.0-SNAPSHOT-jar-with-dependencies.jar conf/router5.conf
```

Each process prints its **Process IP**, **Process Port**, and **Simulated IP**. Copy these values from the destination terminal when attaching. Process ports may change after a restart.

| Configuration | Simulated IP |
|---|---|
| `router1.conf` | `192.168.1.1` |
| `router2.conf` | `192.168.1.100` |
| `router3.conf` | `192.168.2.1` |
| `router4.conf` | `192.168.3.1` |
| `router5.conf` | `192.168.4.1` |

**Process IP + Process Port** identify the real TCP endpoint. **Simulated IP** identifies a router in the topology and is used by LSA, `detect`, and `send`. The four local link slots (0-3) are different from process ports. No router address is hardcoded to a loopback address.

## Commands

Type these at the router's `>>` prompt. Brackets describe placeholders: replace them with actual values and do not type the brackets. A process port must be a number.

| Command | Behavior |
|---|---|
| `attach [Process IP] [Process Port] [Simulated IP] [Link Weight]` | Request a link using the destination's displayed identifiers. The receiver answers `Y` or `N`. Weight must be nonnegative. |
| `start` | Perform HELLO handshakes and exchange LSDB snapshots with TWO_WAY neighbors. |
| `neighbors` | Print directly connected TWO_WAY neighbors. |
| `lsd` | Display the local link-state database. |
| `detect [Destination Simulated IP]` | Compute and print the weighted shortest path. |
| `send [Destination Simulated IP] [Message]` | Deliver an application message along the shortest path. The message may contain spaces. |
| `stats` | Display sent/received counts for HELLO, LSAUPDATE, APP_MESSAGE and ATTACH. |
| `quit` | Close this router's listener and exit the process. |

`attach` stores the approved link but does not synchronize the LSDB. Wait for acceptance before running `start`. Commands accept repeated spaces or tabs between arguments and report usage for missing or extra arguments. `send` preserves whitespace inside the message body.

We keep `connect`, `disconnect`, and `update` as starter-code placeholders for later work; we do not implement dynamic topology changes in PA2.

## Example validation

Build the chain R1-R2-R3-R4-R5, with weights 2, 3, 1 and 2, using the displayed process endpoints. Run `start` on the routers needed to establish each adjacency, wait for synchronization, then run on R1:

```text
detect 192.168.4.1
send 192.168.4.1 Hi Router 5
```

The path should be:

```text
Path found: 192.168.1.1 -> (2) 192.168.1.100 -> (3) 192.168.2.1 -> (1) 192.168.3.1 -> (2) 192.168.4.1
```

For example, R2 forwards without displaying the message payload:

```text
Forwarding message from 192.168.1.1 to 192.168.4.1
Next hop: 192.168.2.1
```

R5 displays:

```text
Received message from 192.168.1.1;
Message: Hi Router 5
```

An unreachable destination prints `Path not found`. For a fresh run with exactly one application message along this chain, R1 has one APP_MESSAGE sent; R2, R3 and R4 each have one received and one sent; R5 has one received. HELLO and LSAUPDATE totals depend on the actual exchange sequence.

Repeat `start`, `detect`, `send`, and `stats` without restarting the processes. Also test a costly direct edge, a cycle, and a router joining an established topology. Repeating `start` must not duplicate an unchanged local link advertisement.

## Implementation notes

- `Router.java` implements command handling, HELLO, LSAUPDATE propagation, hop-by-hop delivery and counter updates.
- `LinkStateDatabase.java` stores the latest known LSA per simulated router and computes weighted paths using Dijkstra with `long` distances.
- `MessageCount.java` protects counters with a shared initialized lock. A broadcast counts once per neighbor. Intermediate application forwarding counts both receipt and transmission.
- `Path.java` stores the router sequence and edge weights and exposes the next hop.
- Only unknown or newer remote LSAs are installed and forwarded. Old/equal sequence numbers and incoming copies of the local router's own LSA are ignored.
- LSDB snapshots and accepted/forwarded updates are deep copies. Database access, link slots and statistics have separate synchronization.
- Network connections and reads use a 5000 ms timeout before object-stream input is constructed. An outgoing attach deliberately waits without a read deadline while the remote user decides Y/N.
- Edge-case comments are marked `EDGE CASE` in the source. Link slot zero and zero-cost edges are valid. Negative costs are rejected. Unknown paths, local delivery, repeated handshakes and concurrent database access are handled explicitly.

## Design walkthrough

1. **`start` and LSDB broadcast.** When a router runs `start`, `processStart()` performs the three-way HELLO handshake with every attached neighbor, which moves each of them to TWO_WAY. As required by PA2, the router then broadcasts its link-state database (LSDB) to all of its TWO_WAY neighbors.

2. **Responder side of the handshake.** The receiving router handles the handshake in a connection-handler thread: it receives a HELLO, replies with a HELLO, waits for the final HELLO, and then also marks the neighbor as TWO_WAY. If this link already appears in its own LSA, it sends a single one-way LSAUPDATE to that neighbor containing its current LSDB. If the link is new, it adds the link to its own LSA with `addNeighborToLSA()` and broadcasts the updated LSDB to all TWO_WAY neighbors. Because the new neighbor is one of the recipients, it immediately learns the whole topology that this router already knows.

3. **Receiving an LSAUPDATE.** Every message that carries LSAs has `sospfType = TYPE_LSU`. When the connection handler receives a packet of this type, it passes it to `receiveLSAUpdate(packet)`. The router merges the LSAs into its LSDB: an LSA is accepted only if its router is unknown or its sequence number is greater than that of the stored copy, and all other LSAs are discarded. If nothing was accepted, propagation stops here. Otherwise, the router forwards the accepted LSAs to all TWO_WAY neighbors except the one it received them from.

4. **Sending an application message.** `processSend()` sends a `TYPE_APP_MESSAGE` packet toward the given destination IP. It calls `nextHopLink(destIP)`, which runs Dijkstra's algorithm on the topology stored in the local LSDB to find the lowest-cost path to the destination. The router then sends the packet to the first neighbor on that path.

5. **Receiving an application message.** When the connection handler receives a `TYPE_APP_MESSAGE` packet, it calls `handleApplicationMessage()`. If the destination IP is this router, the message is delivered and its payload is printed. Otherwise, the router calls `nextHopLink(destIP)` to compute the best path from its own LSDB and forwards the packet it received to the next neighbor on that path, leaving the original source IP and payload unchanged.

6. **Counting received and handshake messages.** We added `stats.countReceived(packet.sospfType)` to the `connectionHandler` method from PA1, so the first packet of every incoming connection is counted by type. Any further message exchanged on the same connection must be counted inside the method that handles it:
   - In `processAttach()`, the requester counts one ATTACH sent when it sends the request and one ATTACH received when the reply arrives. The reply has to be counted here because the Y/N answer comes back on the same connection and never passes through the connection handler.
   - `processStart()` calls `helloHandshake()` for each neighbor, which counts every HELLO it sends and receives. On the responder side, the first HELLO is counted by the connection handler, and the HELLOs sent and received after it are counted inside the handler method.

7. **Counting in `sendOneWayMessage()`.** `sendOneWayMessage()` counts every message it sends, by type. Application messages that are sent or forwarded therefore do not need to be counted again in `processSend()` or `handleApplicationMessage()`, and the same holds for every LSAUPDATE that is broadcast or forwarded.

8. **The `Path` class.** To record the path produced by Dijkstra's algorithm, we created a `Path` class. It keeps two lists: the simulated IPs of the routers along the path, and the cost of each link between consecutive routers. The router uses this information to find the next hop (`nextHopLink`) and to print the path for `detect`.

9. **`MessageCount` and the `lsd` command.** The `MessageCount` class counts how many messages of each type the router has sent and received. Typing `stats` calls the router's `MessageCount.reportMessageCount()` and prints these counts. Typing `lsd` runs `System.out.println(lsd)`; because `LinkStateDatabase` overrides `toString()`, this prints the records stored in the LSDB.

10. **Sequence numbers.** Whenever a neighbor becomes TWO_WAY and the link is added to the router's own LSA with `addLinkToLSA()`, the router increments the sequence number of its own LSA. The sequence numbers of other routers' LSAs are never incremented locally. They are only compared in `merge()`: if the stored copy has a smaller sequence number, it is replaced by the incoming LSA.


