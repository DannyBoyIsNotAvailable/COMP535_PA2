package socs.network.node;

import java.util.ArrayList;
import java.util.List;

/**
 * Calculate the shortest path base on the LinkStateDatabase
 * we use it for detect (obtain the shortest path), and find the next best hop
 */
public class Path {
    
    // routers on the path, from the src to destination (记录simulated IP)
    final List<String> nodes = new ArrayList<>();

    // weight of each edge on this path
    final List<Integer> weights = new ArrayList<>();


    // return the next hop simulated IP
    public String nextHop() {
        if (nodes.size() >= 2) {
            return nodes.get(1);
        }
        return null;
    }
}
