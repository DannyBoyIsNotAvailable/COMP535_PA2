package socs.network.node;

import socs.network.message.LSA;
import socs.network.message.LinkDescription;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Vector;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.Set;

/**
 * The relationship between LinkStateDatabase and LSA:
 * One LinkDescription is an edge on a graph (it represent one certain link)
 * The LSA includes all the LinkDescription that (it connect with its neighbours)
 * LinkStateDatabase is the collection of the LSA at this router
 */


/**
 * Build a graph from the _store
 * _store includes all latest LSA sent by others, in the graph
 * LSA's {linkStateID} represent the node, and it's {links} includes
 * all connection which are the edges, weight is {ld.weight} 
 */



public class LinkStateDatabase {

  /**
 * We use the Dijkstra shortest path algorithm to find the best path
 * 
 * For this algorithm we need to use a prioirityQueue to keep track of those
 * node with shortest distance to the accpeted set
 */

private static class PQEntry {
  String node;
  int distance;
  PQEntry(String node, int distance) {
    this.node = node;
    this.distance = distance;
  }
}

/**
 * Build a weighted graph for with information from LinkStateDatabase
 * router IP -> (neighbor IP -> link weight) <=> linkStateID -> linkID
 * this will cover all the edges for all routers
 */

// NOTE: linkStateID是自己的 linkID是对方的
private Map<String, Map<String, Integer>> constructGraph() {
  Map<String, Map<String, Integer>> graph = new HashMap<>();
  for (LSA lsa : _store.values()) { 
    Map<String, Integer> edges = new HashMap<>();

    for (LinkDescription ld : lsa.links) {
      if (ld.linkID.equals(lsa.linkStateID)) continue; // 会存一个自连接
      edges.put(ld.linkID, ld.weight);
    }
    graph.put(lsa.linkStateID, edges);
  }
  return graph;
}

/**
 * Use the Dijkstra to find the shortest path to one destination of 
 * the constructed graph
 * return the Path object
 */
public synchronized Path findShortestPathTo(String destIP) {
  String src = rd.simulatedIPAddress;
  Map<String, Map<String, Integer>> graph = constructGraph();

  Map<String, Integer> dist = new HashMap<>(); // d[v] initialize as inf
  Map<String, String> prev = new HashMap<>();  // prev[v] nil at first
  Set<String> done = new HashSet<>();  // routers with distance been fixed (already the best)
  PriorityQueue<PQEntry> queue = new PriorityQueue<>((a,b) -> Integer.compare(a.distance, b.distance));

  // Initialize the dist of the src as 0, others as infinity === null
  dist.put(src, 0);
  queue.add(new PQEntry(src, 0));

  while (!queue.isEmpty()) {
    PQEntry cur = queue.poll(); // take out the node with lowest dist to the src
    // A router can be place into queue several times
    // NOTE: different from pesudo code java not allowed to decrease priority
    // we just add another entry
    if (!done.add(cur.node)) continue;
    if (cur.node.equals(destIP)) break;


    // find all edges from current node
    Map<String, Integer> edges = graph.get(cur.node);
    // NOTE: graph.get(cur.node) might return null
    // because eg: R2's lsa {(R2->R1), (R2->R3)} but R3's lsa haven't been
    // sent to the R1 yet. Thus when R1 construct the graph, we can get R3 lsa and it's edges
    if (edges == null) continue;  // we just can't move forward from this point , ignore it

    for (Map.Entry<String, Integer> edge : edges.entrySet()) { // iterate through all node connected with it
      String neighbor = edge.getKey();
      if (done.contains(neighbor)) continue; // when it done, the node already has the shortest dist
      // when the new distance is shorter than the old one
      if (dist.get(neighbor) == null || dist.get(neighbor) > cur.distance + edge.getValue()) {
        dist.put(neighbor, cur.distance + edge.getValue());
        prev.put(neighbor, cur.node); // second entry is your previous node
        queue.add(new PQEntry(neighbor, cur.distance + edge.getValue()));
      }
    }
  }

  if (!dist.containsKey(destIP)) return null;

  // follow the prev list to move back to the src and build the path
  Path path = new Path();
  // we need to reverse the order from  dst->src to src->dst
  LinkedList<String> reversePath = new LinkedList<>();
  for (String s = destIP; s != null; s = prev.get(s)) {
    reversePath.addFirst(s);
  }
  path.nodes.addAll(reversePath);
  for (int i=0; i < reversePath.size()-1; i++) {
    Map<String, Integer> eg = graph.get(reversePath.get(i));
    path.weights.add(eg.get(reversePath.get(i+1)));
  }
  return path;

}





  //linkID => LSAInstance
  HashMap<String, LSA> _store = new HashMap<String, LSA>();

  private RouterDescription rd = null; 

  public LinkStateDatabase(RouterDescription routerDescription) {
    rd = routerDescription;
    LSA l = initLinkStateDatabase();
    _store.put(l.linkStateID, l);
  }

  /**
   * output the shortest path from this router to the destination with the given IP address
   * <p/>
   * This method should use Dijkstra's algorithm with link weights (not hop count) to find the shortest path.
   * The weights are stored in the weight field of LinkDescription objects in the LSA entries.
   * <p/>
   * format: source ip address -> ip address -> ... -> destination ip
   *
   * @param destinationIP the simulated IP address of the destination router
   * @return the shortest path as a string, or null if no path exists
   */
  String getShortestPath(String destinationIP) {  // <-------------
    //TODO: fill the implementation here
    Path path = findShortestPathTo(destinationIP);
    if (path == null) return null;
    StringBuilder sb = new StringBuilder(path.nodes.get(0));
    for (int i = 0; i < path.weights.size(); i++) {
      sb.append(" -> (").append(path.weights.get(i)).append(") ").append(path.nodes.get(i + 1));
    }
    return sb.toString();
  }

  //initialize the linkstate database by adding an entry about the router itself
  private LSA initLinkStateDatabase() {
    LSA lsa = new LSA();
    lsa.linkStateID = rd.simulatedIPAddress;
    lsa.lsaSeqNumber = Integer.MIN_VALUE;
    LinkDescription ld = new LinkDescription();
    ld.linkID = rd.simulatedIPAddress;
    ld.portNum = -1;
    ld.tosMetrics = 0;
    ld.weight = 0; //self-link has weight 0
    lsa.links.add(ld);
    return lsa;
  }


  public String toString() {
    StringBuilder sb = new StringBuilder();
    for (LSA lsa: _store.values()) {
      sb.append(lsa.linkStateID).append("(" + lsa.lsaSeqNumber + ")").append(":\t");
      for (LinkDescription ld : lsa.links) {
        sb.append(ld.linkID).append(",").append(ld.portNum).append(",").
                append(ld.weight).append("\t");
      }
      sb.append("\n");
    }
    return sb.toString();
  }


  /**
   * When there is one conection become 2 way, we can add it's link description
   * into the LSA, and increase the seq number so later other routers will realize this update
   * NOTE: For the self link at initLinkStateDatabase() it use it's own simulatedIP to initialize
   * it's link description so here we do the same thing (use neighbor's simulatedIP to represent the new ld).
   */
  public synchronized boolean addLinkToLSA(String neighborSimuIP, int port, int weight) {
    LSA ownLSA = _store.get(rd.simulatedIPAddress); // use the router's own simulatedIP to get it's own LSA
    for (LinkDescription ld : ownLSA.links) {
      if (ld.linkID.equals(neighborSimuIP)) {
        return false;
      }
    }
    LinkDescription ld = new LinkDescription();
    ld.linkID = neighborSimuIP;
    ld.portNum = port;
    ld.tosMetrics = 0;
    ld.weight = weight; 
    ownLSA.links.add(ld);
    ownLSA.lsaSeqNumber++;
    return true;
    
  }


  /**
   * When the router recieived others LSAs, it need identify which LSA haven't
   * exist inside the _store or the sequenceNumber of that LSA been updated
   * We will add those new or new version LSA into the _store
   * 
   * 
   * AI advice1: check if the incomingLSA == null
   * AI advice2: you can just skip your own LSA
   */
  public synchronized List<LSA> merge(List<LSA> newLSAs) {
    List<LSA> result = new ArrayList<>();
    for (LSA incomingLSA : newLSAs) {
      if (incomingLSA == null || incomingLSA.linkStateID == null) {
        continue;
      }
      if (!_store.containsKey(incomingLSA.linkStateID) ||
          _store.get(incomingLSA.linkStateID).lsaSeqNumber < incomingLSA.lsaSeqNumber) {
         // That means this LSA should be stored
        result.add(incomingLSA);
        _store.put(incomingLSA.linkStateID, incomingLSA);
      }
    }
    return result; // For checking if the merged list is empty or not
    // then we could determin wheather or not  we need to make a broadcast for this update.
  }

  /**
   * 1: Here we take the lock and all the LSA inside
   *  the _src before we send our LSAs to other router
   * 2: Another routine is we take the lock during sending LSAs
   * at this case we don't need to do any copy
   * 
   * => Why we choose 2: the problem is the sending process might be time-consuming
   * that means we need to hold the lock for quite long time and other thread that
   * need to update the _src might need to wait for the lock ...
   * Return type Vector<> : this is defined inside SOSPFPacket
   */
  public synchronized Vector<LSA> deepCopy() {
      Vector<LSA> copy = new Vector<>();
      for (LSA lsa : _store.values()) {
        copy.add(copy(lsa));
      }
      return copy;
  }

  // deep copy lsa, create the new LinkDescription
  public LSA copy(LSA lsa) {
    LSA new_lsa = new LSA();
    new_lsa.linkStateID = lsa.linkStateID;
    new_lsa.lsaSeqNumber = lsa.lsaSeqNumber;
    for (LinkDescription ld : lsa.links) {
      LinkDescription new_ld = new LinkDescription();
      new_ld.linkID = ld.linkID;
      new_ld.portNum = ld.portNum;
      new_ld.tosMetrics = ld.tosMetrics;
      new_ld.weight =ld.weight;
      new_lsa.links.add(new_ld);
    }
    return new_lsa;
  }




}


