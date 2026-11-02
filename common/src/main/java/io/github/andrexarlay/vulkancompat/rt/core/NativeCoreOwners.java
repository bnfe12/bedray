package io.github.andrexarlay.vulkancompat.rt.core;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongConsumer;

/** Tracking allocation precedes native acceptance; acknowledgement after create/destroy is a primitive write. */
final class NativeCoreOwners {
    static final class Reservation {
        private long handle;
        void accepted(long handle) { this.handle = handle; }
        long handle() { return handle; }
    }
    private final Set<Reservation> owners = ConcurrentHashMap.newKeySet();

    Reservation reserve() {
        var reservation = new Reservation();
        owners.add(reservation);
        return reservation;
    }

    void failed(Reservation reservation) {
        if (reservation.handle == 0) owners.remove(reservation);
    }

    Reservation require(long handle) {
        if (handle != 0) {
            for (Reservation owner : owners) if (owner.handle == handle) return owner;
        }
        throw new IllegalArgumentException("native core is not owned by this library; handle=0x" + Long.toHexString(handle));
    }

    void released(Reservation reservation) {
        reservation.handle = 0; // Before collection cleanup: a later failure cannot destroy the native object twice.
        owners.remove(reservation);
    }

    void destroyAll(LongConsumer destroy) {
        for (Reservation owner : owners) {
            long handle = owner.handle;
            if (handle != 0) destroy.accept(handle);
        }
    }
}
