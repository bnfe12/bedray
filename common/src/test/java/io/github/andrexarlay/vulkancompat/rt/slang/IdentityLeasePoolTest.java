package io.github.andrexarlay.vulkancompat.rt.slang;

import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class IdentityLeasePoolTest {
    @Test void independentFeatureLifetimesShareOneOwnerUntilTheFinalLeaseCloses() {
        var creates = new AtomicInteger(); var closes = new AtomicInteger();
        var pool = new IdentityLeasePool<Object, Object>(key -> { creates.incrementAndGet(); return new Object(); }, value -> closes.incrementAndGet());
        Object device = new Object(); var renderer = pool.acquire(device); var velocity = pool.acquire(device); var atmosphere = pool.acquire(device);
        Object runtime = renderer.resource();
        assertSame(runtime, velocity.resource()); assertSame(runtime, atmosphere.resource()); assertEquals(1, creates.get());
        renderer.close(); atmosphere.close(); assertEquals(0, closes.get()); assertSame(runtime, velocity.resource());
        velocity.close(); velocity.close(); assertEquals(1, closes.get());
        assertThrows(IllegalStateException.class, velocity::resource);
        var recreated = pool.acquire(device); assertNotSame(runtime, recreated.resource()); assertEquals(2, creates.get()); recreated.close();
    }
    @Test void equalHostWrappersDoNotAccidentallyShareDifferentDevices() {
        var creates = new AtomicInteger();
        var pool = new IdentityLeasePool<String, Object>(key -> { creates.incrementAndGet(); return new Object(); }, value -> {});
        var first = pool.acquire(new String("device")); var second = pool.acquire(new String("device"));
        assertNotSame(first.resource(), second.resource()); assertEquals(2, creates.get()); first.close(); second.close();
    }
    @Test void finalCloseRefusalRetainsTheSameLeaseAndResourceForRetry() {
        var refuse = new AtomicBoolean(true); var closes = new AtomicInteger();
        var pool = new IdentityLeasePool<Object, Object>(key -> new Object(), value -> {
            closes.incrementAndGet(); if (refuse.get()) throw new IllegalStateException("recording pending");
        });
        Object device = new Object(); var lease = pool.acquire(device); Object runtime = lease.resource();
        assertThrows(IllegalStateException.class, lease::close); assertSame(runtime, lease.resource());
        var extra = pool.acquire(device); assertSame(runtime, extra.resource()); extra.close();
        refuse.set(false); lease.close(); assertEquals(2, closes.get());
        assertThrows(IllegalStateException.class, lease::resource);
    }
    @Test void aFailedFactoryDoesNotPublishOrLeakAnOwner() {
        var refuse = new AtomicBoolean(true);
        var pool = new IdentityLeasePool<Object, Object>(key -> {
            if (refuse.get()) throw new IllegalStateException("unavailable"); return new Object();
        }, value -> {});
        Object device = new Object(); assertThrows(IllegalStateException.class, () -> pool.acquire(device));
        refuse.set(false); var lease = pool.acquire(device); assertNotNull(lease.resource()); lease.close();
    }
}
