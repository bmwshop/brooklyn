package brooklyn.event.basic;

import brooklyn.entity.Entity;
import brooklyn.event.Sensor;
import brooklyn.event.SensorEvent;

import com.google.common.base.Objects;

/**
 * A {@link SensorEvent} containing data from a {@link Sensor} generated by an {@link Entity}.
 */
public class BasicSensorEvent<T> implements SensorEvent<T> {
    private final Sensor<T> sensor;
    private final Entity source;
    private final T value;
    private final long timestamp;
    
    public T getValue() { return value; }

    public Sensor<T> getSensor() { return sensor; }

    public Entity getSource() { return source; }

    public long getTimestamp() { return timestamp; }

    /** arguments should not be null (except in certain limited testing situations) */
    public BasicSensorEvent(Sensor<T> sensor, Entity source, T value) {
        this(sensor, source, value, 0);
    }
    
    public BasicSensorEvent(Sensor<T> sensor, Entity source, T value, long timestamp) {
        this.sensor = sensor;
        this.source = source;
        this.value = value;

        if (timestamp > 0) {
            this.timestamp = timestamp;
        } else {
            this.timestamp = System.currentTimeMillis();
        }
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(sensor, source, value);
    }   

    /**
     * Any SensorEvents are equal if their sensor, source and value are equal.
     * Ignore timestamp for ease of use in unit tests.   
     */
    @Override
    public boolean equals(Object o) {
        if (!(o instanceof SensorEvent)) return false;
        SensorEvent<?> other = (SensorEvent<?>) o;
        return Objects.equal(sensor, other.getSensor()) && Objects.equal(source, other.getSource()) &&
                Objects.equal(value, other.getValue());
    }
    
    @Override
    public String toString() {
        return source+"."+sensor+"="+value+" @ "+timestamp;
    }
}
