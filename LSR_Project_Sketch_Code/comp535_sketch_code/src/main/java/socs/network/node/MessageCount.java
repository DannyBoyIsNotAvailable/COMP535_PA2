package socs.network.node;


/**
 * Count number of message through the router (sent/received since it started),
 * it's grouped by number of 
 */

public class MessageCount {

    // EDGE CASE: concurrent handlers and stats share one initialized lock.
    // A null lock would crash on the very first packet or stats command.
    private final Object count_obj = new Object();
    
    private final String[] TYPE_MESSAGE = {"HELLO", "LSAUPDATE", "APP_MESSAGE", "ATTACH"};
    private int[] received = new int [TYPE_MESSAGE.length]; // each entry count the number of certain message it received
    private int[] sent = new int [TYPE_MESSAGE.length];

    public void countReceived(int type_idx) {
        synchronized(count_obj) {
            if (type_idx >= 0 && type_idx < TYPE_MESSAGE.length) {
            received[type_idx]++;
            }
        }
    }

    public void countSent(int type_idx) {
            synchronized(count_obj) {
                if (type_idx >= 0 && type_idx < TYPE_MESSAGE.length) {
                sent[type_idx]++;
            }
        }
        
    }

    public String reportMessageCount() {
        synchronized(count_obj) {
            StringBuilder sb = new StringBuilder();
            for (int i=0; i<TYPE_MESSAGE.length; i++) {
                sb.append(TYPE_MESSAGE[i]).append(" Sent: ").append(sent[i]);
                sb.append("\n");
                sb.append(TYPE_MESSAGE[i]).append(" Received: ").append(received[i]);
                sb.append("\n");
            }
            return sb.toString();
        }
    }

}
