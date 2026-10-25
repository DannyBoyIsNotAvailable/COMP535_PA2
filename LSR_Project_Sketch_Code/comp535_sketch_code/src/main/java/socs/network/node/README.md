# PA2 Implementation and Running Notes

This is the group's implementation README. Run all commands below from the Java project directory containing `pom.xml`, `src/`, and `conf/` (`LSR_Project_Sketch_Code/comp535_sketch_code` in the repository). For the supplied starter instructions, see the [project README](../../../../../../README.md).

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

Open five VSCode terminals in the Java project directory. Run one command per terminal:

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

`connect`, `disconnect`, and `update` are retained as starter-code placeholders for later work; they do not implement dynamic topology changes in PA2.

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

