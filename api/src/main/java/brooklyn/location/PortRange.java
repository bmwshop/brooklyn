package brooklyn.location;

/**
 * A range of ports (indicator for Location and other APIs).
 * Using methods {@code PortRanges.fromXxx(...)} this is adaptable from a number, a string, or a collection of numbers or a strings.
 * String may be of the form:
 *   <li> "80": just 80
 *   <li> "8080-8090": limited range sequentially; ie try 8080, then 8081, ..., then 8090, then give up
 *   <li> "8080-8000": as above, but descending; ie try 8080, then 8079, ..., then 8000, then give up
 *   <li> "8000+": unlimited range sequentially; ie try 8000, then 8001, then 8002, etc
 *   <li> "80,8080,8000,8080-8099": different ranges, in order; ie try 80, then 8080, then 8000, then 8080 (again), then 8081, ..., then 8099, then give up
 * Ranges (but not lists) may be preceeded by "!" to indicate a randomly selected port:
 * 
 * @see brooklyn.location.basic.PortRanges
 */
//MAYDO could have:   <li> "~32168-65535" (or "~32168-"): try randomly selected numbers in range 32168-65535 (MAX_PORT) until all have been tried
public interface PortRange extends Iterable<Integer> {
    /**
     * Whether there are any ports in the range.
     */
    boolean isEmpty();
    
    /**
     * Note: this method is only here for use with "groovy truth". Users are strongly discouraged  
     * from calling it directly.
     *  
     * @return {@code !isEmpty()}; i.e. true if there is at least one port in the range; false otherwise
     */
    boolean asBoolean();
}
