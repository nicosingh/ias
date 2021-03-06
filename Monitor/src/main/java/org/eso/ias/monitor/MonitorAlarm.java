package org.eso.ias.monitor;

import org.eso.ias.types.Alarm;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * An enumerated with all the alarms generated by the Monitor tool.
 *
 * GLOBAL is a multiplicity alarm that is activated if at least one of the others
 * is SET. For that reason, {@link #set(Alarm, String)} throws exception
 * if the MonitorAlarm is GLOBAL.
 *
 * The monitor tool periodically sends these alarms.
 */
public enum MonitorAlarm {

    // Health
    PLUGIN_DEAD,
    CONVERTER_DEAD,
    SUPERVISOR_DEAD,
    SINK_DEAD,
    CLIENT_DEAD,
    CORETOOL_DEAD,
    GLOBAL; // Multiplicity

    /**
     * The ID of each alarm
     */
    public final String id;

    /**
     * The last alarm sent for this monitorAlarm
     */
    private AtomicReference<Alarm> alarm = new AtomicReference<>(Alarm.CLEARED);

    /**
     * The IDs of the faulty monitored tools (plugins, converters...)
     * to be set as property of the IASValue
     */
    private AtomicReference<String> faultyIds = new AtomicReference<>("");

    /**
     * Constructor
     */
    private MonitorAlarm() {
        this.id = "IASMON-"+this.name();
    }

    /**
     * Calculate and return the Alarm state of GLOBAL
     *
     * @return the value of the GLOBAL alarm (multiplicity)
     */
    private Alarm getGlobalAlarm() {
        if (this!=GLOBAL) {
            throw new UnsupportedOperationException("Must be called for GLOABL only");
        }

        Alarm ret = Alarm.cleared();
        for (MonitorAlarm monAlarm: MonitorAlarm.values()) {
            if (monAlarm!=GLOBAL) {
                Optional<Integer> priorityLvl = monAlarm.getAlarm().priorityLevel;
                if (priorityLvl.isPresent()) {
                    if (!ret.priorityLevel.isPresent() || priorityLvl.get()>ret.priorityLevel.get()) {
                        ret = Alarm.fromPriority(priorityLvl.get());
                    }
                }
            }
        }
    return ret;
    }

    /**
     * Get and return the properties of the GLOBAL alarm.
     *
     * The faulty IDs of GLOBAL is composed of all the faulty IDs of the MonitorAlarms
     * that are set
     *
     * @return the properties of the GLOBAL alarm.
     */
    private String getGlobalProperties() {
        if (this!=GLOBAL) {
            throw new UnsupportedOperationException("Must be called for GLOABL only");
        }

        StringBuilder ret = new StringBuilder();
        for (MonitorAlarm monAlarm: MonitorAlarm.values()) {
            if (monAlarm != GLOBAL && monAlarm.getAlarm().isSet() && !monAlarm.getProperties().isEmpty()) {
                if (!ret.toString().isEmpty()) {
                    ret.append(',');
                }
                ret.append(monAlarm.getProperties());
            }
        }
        return ret.toString();
    }

    /**
     * Getter
     *
     * @return The alarm
     */
    public Alarm getAlarm() {
        if (this==GLOBAL) {
            return getGlobalAlarm();
        } else {
            return alarm.get();
        }
    }

    /**
     * Getter
     *
     * @return The faulty IDs
     */
    public String getProperties() {
        if (this!=GLOBAL) {
            return faultyIds.get();
        } else {
            return getGlobalProperties();
        }
    }

    /**
     * Clear the alarm
     */
    public void clear() {
        if (this==GLOBAL) {
            throw new UnsupportedOperationException("Cannot set the state of GLoBAL");
        }
        alarm.set(Alarm.cleared());
        faultyIds.set("");
    }

    /**
     * Set the alarm.
     *
     * This method can be called for each {@link MonitorAlarm} apart of GLOBAL.
     *
     * @param alarm the not null alarm to set
     * @param faultyIds the comma separated IDs of tools that did not sent the HB
     */
    public void set(Alarm alarm, String faultyIds) {
        Objects.requireNonNull(alarm);
        Objects.requireNonNull(faultyIds);
        if (this==GLOBAL) {
            throw new UnsupportedOperationException("Cannot set the state of GLoBAL");
        }
        if (alarm==Alarm.CLEARED) {
            clear();
        } else {
            this.alarm.set(alarm);
            this.faultyIds.set(faultyIds);
        }
    }

    /**
     * Set an alarm with the default priority
     *
     * @param faultyIds the comma separated IDs of tools that did not sent the HB
     */
    public void set(String faultyIds) {
        set(Alarm.getSetDefault(),faultyIds);
    }
}

