// Decompiled and restored by ZedWAre.
// Raven 1.8.9 script body. No camera/motion writes, threads or raw key-state writes.
// Physics parameters are a model, not a claim about a particular server plugin.
class PearlTrack {
    int id, seenTick, samples;
    Vec3 pos, velocity;
    long sampledAt;
    boolean handled;
}

class Intercept {
    Vec3 origin, point, basePoint;
    float yaw, pitch;
    double flightTicks, leadTicks, calibrationTicks;
    long computedAt;
}

Map<Integer, PearlTrack> pearls = new HashMap<Integer, PearlTrack>();
Map<String, Boolean> clearanceCache = new HashMap<String, Boolean>();
PearlTrack target;
Intercept aim, display, motionAim;
boolean enabled, locked, usePermit, attemptedUse, usePacketObserved;
boolean ownsStasis;
float observedYaw, observedPitch, offeredYaw, offeredPitch;
volatile boolean disturbed, useConflict;
int plannedTick = -1, blockUseThroughTick = -1;
int localPlayerId = -1;
int tick, stableTicks, brakeTicks, oldSlot = -1, fireSlot = -1;
int selectedTick, observedSlot = -1, lastPlayerTick = -1, pingMs;
long lockedAt, displayUntil, nextTriggerAt, ownThrowAt = -10000;
long selectedAt, nextUseAttemptAt;
Vec3 lastPlayerPos, ownThrowEye;
String dimension = "", status = "Watching";
final double STOP_EPS = 0.002;

void onLoad() {
    modules.registerDescription("By Adnin | Predictive pearl interception (experimental)");
    modules.registerButton("Vanilla fireball", true);
    modules.registerSlider("Fireball initial speed", " blocks/tick", 0, 0, 4, 0.05);
    modules.registerSlider("Fireball acceleration", " blocks/tick^2", 0.1, 0, 0.5, 0.005);
    modules.registerSlider("Fireball drag", "", 0.95, 0.8, 1.0, 0.005);
    // Used ONLY with Vanilla fireball disabled (constant-speed server model).
    modules.registerSlider("Fireball speed", " blocks/tick", 1.0, 0.1, 4.0, 0.05);
    modules.registerSlider("Pearl gravity", " blocks/tick^2", 0.03, 0, 0.1, 0.005);
    modules.registerSlider("Pearl drag", "", 0.99, 0.9, 1.0, 0.005);
    modules.registerSlider("Manual ping", " ms RTT", 100, 0, 300, 5);
    modules.registerSlider("Own ping lead factor", " x RTT", 1.0, 0, 1.5, 0.05);
    modules.registerSlider("Prediction factor", " ticks", 0, -5, 5, 0.1);
    modules.registerSlider("Extra lead", " ms", 25, 0, 250, 5);
    modules.registerSlider("Spawn offset", " blocks", 0, 0, 2, 0.05);
    modules.registerSlider("Detect range", " blocks", 48, 8, 96, 4);
    modules.registerSlider("Max flight", " ticks", 40, 5, 80, 5);
    modules.registerSlider("Lock timeout", " ms", 1200, 300, 3000, 50);
    // Same selection delay and restore control as PearlFireball.
    modules.registerSlider("Slot settle delay", " ms", 50, 0, 500, 25);
    modules.registerButton("Restore slot", true);
    modules.registerSlider("Aim Speed", " deg/t", 20, 1, 180, 1);
    modules.registerButton("Auto Stasis", false);
    modules.registerButton("Counter brake", true);
    modules.registerButton("Ignore own throws", true);
    modules.registerButton("Draw prediction", true);
}

double option(String name) { return modules.getSlider(getScriptName(), name); }
boolean button(String name) { return modules.getButton(getScriptName(), name); }

void onEnable() {
    enabled = true;
    resetSession();
    // Pearls already present when enabling are still tracked, but not fired at.
    if (world.exists()) {
        samplePearls();
        for (PearlTrack p : pearls.values()) p.handled = true;
    }
}

void onDisable() {
    enabled = false;
    finish("Disabled", false);
    resetSession();
}

void resetSession() {
    releaseAutoStasis();
    locked = false;
    disturbed = false;
    useConflict = usePermit = attemptedUse = usePacketObserved = false;
    motionAim = null;
    plannedTick = blockUseThroughTick = -1;
    selectedAt = nextUseAttemptAt = 0;
    pearls.clear();
    target = null;
    aim = null;
    display = null;
    lastPlayerPos = null;
    lastPlayerTick = -1;
    oldSlot = fireSlot = -1;
    // Baseline from the normal held slot, NOT an acknowledgement from the server.
    observedSlot = world.exists() && client.getPlayer() != null ? inventory.getSlot() : -1;
    // BedDefender's initial local-angle baseline, not a server acknowledgement.
    observedYaw = observedSlot >= 0 ? client.getPlayer().getYaw() : 0;
    observedPitch = observedSlot >= 0 ? client.getPlayer().getPitch() : 0;
    stableTicks = brakeTicks = tick = 0;
    nextTriggerAt = 0;
    ownThrowAt = -10000;
    ownThrowEye = null;
    dimension = world.exists() ? world.getDimension() : "";
    status = "Watching";
}

boolean usable() {
    Entity p = client.getPlayer();
    return world.exists() && p != null && !p.isDead() && client.getScreen().isEmpty();
}

boolean supportedGround(Entity p) {
    return p.onGround() && !p.isInLiquid() && !p.isOnLadder()
        && !p.isRiding() && !client.isFlying();
}

boolean stasisActive() {
    return modules.isEnabled("Stasis");
}

boolean canInterceptFrom(Entity p) {
    // Stasis is an explicit movement-state override, not a suspension detector.
    // Do not infer its state from onGround, flying, motion or position samples.
    if (p == null) return false;
    boolean stasis = stasisActive();
    // If the player disables our temporary Stasis, cancel; never fight them by
    // enabling it again during the current attempt, even if they are grounded.
    return (!ownsStasis || stasis) && (stasis || supportedGround(p));
}

boolean startAutoStasis() {
    if (!button("Auto Stasis") || stasisActive()) return true;
    // Only own an off -> on transition performed by this script. A Stasis that
    // was already enabled belongs to the player and must stay enabled afterward.
    ownsStasis = true;
    try {
        modules.enable("Stasis");
        if (stasisActive()) return true;
    } catch (Exception ex) {
        // Abort instead of inventing a hovering state if the module is missing
        // or rejects activation. release also handles a partial enable.
    }
    releaseAutoStasis();
    status = "Waiting: Auto Stasis could not enable";
    nextTriggerAt = client.time() + 300;
    return false;
}

void releaseAutoStasis() {
    if (!ownsStasis) return;
    try {
        if (stasisActive()) modules.disable("Stasis");
        ownsStasis = stasisActive();
        if (!ownsStasis) return;
    } catch (Exception ex) {
        // Preserve ownership for a later cleanup attempt, but never skip the
        // rest of input/slot cleanup merely because another module failed.
    }
    client.print("&e[" + getScriptName() + "] Could not release Auto Stasis; disable Stasis manually.");
}

boolean still(Entity p, Vec3 previous) {
    if (p == null || !supportedGround(p) || previous == null
        || distance(p.getPosition(), previous) > STOP_EPS) return false;
    Vec3 m = client.getMotion();
    // Grounded Minecraft retains a downward gravity motion; use actual vertical
    // displacement instead of demanding motionY == 0. Reject upward launches.
    return Math.hypot(m.x, m.z) <= STOP_EPS && m.y <= STOP_EPS
        && distance(p.getPosition(), previous) <= STOP_EPS;
}

boolean movementReady(Entity p) {
    if (!canInterceptFrom(p)) return false;
    // The switch alone satisfies movement readiness. Do not wait for two still
    // samples, and re-read the switch at aim/use time in case it was disabled.
    return stasisActive() || (stableTicks >= 2 && still(p, lastPlayerPos));
}

void onPreUpdate() {
    if (!enabled) return;
    tick++;
    motionAim = null;
    plannedTick = -1;
    if (!usable()) {
        finish("Paused: GUI / world / player", true);
        if (!world.exists() || client.getPlayer() == null) pearls.clear();
        else for (PearlTrack p : pearls.values()) p.handled = true;
        lastPlayerPos = null;
        lastPlayerTick = -1;
        display = null;
        return;
    }
    Entity player = client.getPlayer();
    localPlayerId = player.entityId;
    if (disturbed) {
        disturbed = false;
        finish("Cancelled: server correction / knockback", true);
        lastPlayerPos = null;
        display = null;
    }
    String dim = world.getDimension();
    if (!dim.equals(dimension) || player.getTicksExisted() < lastPlayerTick
        || (lastPlayerPos != null && distance(lastPlayerPos, player.getPosition()) > 8)) {
        // Never restore an old-world hotbar slot into a new player/world.
        oldSlot = -1;
        finish("World / position changed", false);
        resetSession();
    }
    dimension = dim;
    lastPlayerTick = player.getTicksExisted();
    boolean stationary = !stasisActive() && still(player, lastPlayerPos);
    lastPlayerPos = copy(player.getPosition());
    samplePearls();

    if (locked) {
        if (!canInterceptFrom(player)) {
            finish("Cancelled: airborne without Stasis / unsupported movement", true);
            return;
        }
        if (useConflict || keybinds.isMouseDown(1)) {
            finish("Cancelled: manual / competing item use", true);
            return;
        }
        if (client.time() - lockedAt >= option("Lock timeout")) {
            finish("Cancelled: stop/aim timeout", true);
            return;
        }
        if (target == null || target.seenTick != tick || !validTarget(target)) {
            finish("Cancelled: target or fireball unavailable", true);
            return;
        }
        if (inventory.getSlot() != fireSlot) {
            // Honor manual hotbar changes instead of fighting the player.
            oldSlot = -1;
            finish("Cancelled: slot changed", true);
            return;
        }
        // Like PearlFireball, re-select a replacement if this stack vanished.
        if (!isFireball(inventory.getStackInSlot(fireSlot))) {
            int replacement = findFireball();
            if (replacement < 0) {
                finish("Cancelled: no fireball remaining", true);
                return;
            }
            selectExpectedSlot(replacement, client.time());
        }
        stableTicks = stationary ? stableTicks + 1 : 0;
        aim = solve(target, eye(player), client.time());
        if (aim != null) show(aim);
        else display = null;
        status = movementReady(player) ? "Waiting for clear intercept" : "Stopping: movement locked";
        return;
    }

    aim = null;
    // Auto Stasis may acquire while falling; do not require it to be enabled
    // before a valid pearl/fireball candidate has actually been selected.
    if (client.time() < nextTriggerAt || (!canInterceptFrom(player) && !button("Auto Stasis"))
        || keybinds.isMouseDown(1)) return;
    int slot = findFireball();
    if (slot < 0) { status = "Watching: no hotbar fireball"; return; }
    PearlTrack best = null;
    Intercept bestAim = null;
    for (PearlTrack p : pearls.values()) {
        if (p.handled || !validTarget(p)) continue;
        Intercept candidate = solve(p, eye(player), client.time());
        if (candidate != null && (bestAim == null
            || candidate.flightTicks < bestAim.flightTicks
            || (candidate.flightTicks == bestAim.flightTicks && p.id < best.id))) {
            best = p;
            bestAim = candidate;
        }
    }
    if (best == null) { status = "Watching: no clear intercept"; return; }
    if (!startAutoStasis()) return;
    try {
        if (!enabled || !usable() || !canInterceptFrom(client.getPlayer())) {
            releaseAutoStasis();
            return;
        }
        // The first solve only filters candidates. Recalculate after enabling
        // Stasis, from the current eye position, before offering any firing angle.
        bestAim = solve(best, eye(client.getPlayer()), client.time());
    } catch (Exception ex) {
        releaseAutoStasis();
        nextTriggerAt = client.time() + 300;
        status = "Cancelled: prediction unavailable after Stasis";
        return;
    }
    if (bestAim == null) {
        releaseAutoStasis();
        nextTriggerAt = client.time() + 300;
        status = "Watching: no clear intercept after Stasis";
        return;
    }
    target = best;
    aim = bestAim;
    show(aim);
    locked = true;
    useConflict = false;
    lockedAt = client.time();
    stableTicks = brakeTicks = 0;
    oldSlot = inventory.getSlot();
    selectExpectedSlot(slot, client.time());
    status = stasisActive() ? "Stasis enabled: aiming" : "Stopping: movement locked";
}

void selectExpectedSlot(int slot, long now) {
    // PearlFireball's selection flow: local selection, then settle for at least
    // one normal player update. Never manually synthesize a C09 packet.
    fireSlot = slot;
    selectedAt = now;
    selectedTick = tick;
    nextUseAttemptAt = now + (long) Math.max(0, Math.min(500, option("Slot settle delay")));
    motionAim = null;
    plannedTick = -1;
    if (inventory.getSlot() != slot) inventory.setSlot(slot);
}

void samplePearls() {
    long now = client.time();
    Entity player = client.getPlayer();
    if (player == null) return;
    double drag = option("Pearl drag"), gravity = option("Pearl gravity");
    for (Entity e : world.getEntities()) {
        if (e == null || e.type == null || !e.type.toLowerCase().contains("enderpearl")
            || e.isDead() || distance(e.getPosition(), player.getPosition()) > option("Detect range")) continue;
        PearlTrack p = pearls.get(e.entityId);
        Vec3 pos = copy(e.getPosition());
        if (p == null) {
            p = new PearlTrack();
            p.id = e.entityId;
            p.pos = pos;
            p.seenTick = tick;
            p.sampledAt = now;
            p.samples = 1;
            // API has no projectile owner. This is explicitly only a heuristic.
            p.handled = button("Ignore own throws") && ownThrowEye != null
                && now - ownThrowAt < 650 && distance(pos, ownThrowEye) < 5;
            pearls.put(p.id, p);
            continue;
        }
        if (p.seenTick == tick) continue;
        Vec3 delta = subtract(pos, p.pos);
        double length = norm(delta);
        if (tick - p.seenTick == 1 && length > 0.001 && length < 4) {
            // Last tick displacement is velocity BEFORE drag/gravity; predict
            // using the velocity for the NEXT tick from the new position.
            Vec3 next = new Vec3(delta.x * drag, delta.y * drag - gravity, delta.z * drag);
            boolean consistent = p.velocity == null || distance(delta, p.velocity) < 0.35;
            p.samples = consistent ? p.samples + 1 : 1;
            p.velocity = next;
        } else {
            p.samples = 1;
            p.velocity = null;
        }
        p.pos = pos;
        p.seenTick = tick;
        p.sampledAt = now;
    }
    Iterator<PearlTrack> it = pearls.values().iterator();
    while (it.hasNext()) if (tick - it.next().seenTick > 2) it.remove();
}

boolean validTarget(PearlTrack p) {
    return p.seenTick == tick && p.samples >= 3 && p.velocity != null
        && client.time() - p.sampledAt <= 150;
}

int configuredPing() {
    // User-supplied full round-trip time; never read the tab-list/API ping.
    return (int) Math.max(0, Math.min(300, option("Manual ping")));
}

void onPostPlayerInput() {
    if (!locked) return;
    if (!usable() || !canInterceptFrom(client.getPlayer()) || disturbed || useConflict || keybinds.isMouseDown(1)
        || client.time() - lockedAt >= option("Lock timeout")) {
        finish("Cancelled: input safety timeout / interruption", true);
        return;
    }
    client.setForward(0);
    client.setStrafe(0);
    client.setJump(false);
    client.setSneak(false);
    client.setSprinting(false);
    // Stasis owns movement: suppress inputs, but never counter-steer against it
    // or write player velocity. This also applies if onGround is still true.
    if (stasisActive()) return;
    Entity player = client.getPlayer();
    Vec3 motion = client.getMotion();
    double speed = Math.hypot(motion.x, motion.z);
    if (!button("Counter brake") || !supportedGround(player) || speed <= STOP_EPS
        || brakeTicks >= 3 || stableTicks > 0) return;
    // World-vector -> visible-yaw inputs. Silent aim is disabled while moving.
    // One ordinary direction key at most; coast near zero instead of applying
    // fractional analog input or overshooting with a reverse key pulse.
    float[] inputs = brakeInput(motion, player.getYaw(), player.getMoveAttribute());
    if (inputs[0] == 0 && inputs[1] == 0) return;
    brakeTicks++;
    client.setForward(inputs[0]);
    client.setStrafe(inputs[1]);
}

float[] brakeInput(Vec3 motion, float yaw, double moveAttribute) {
    double speed = Math.hypot(motion.x, motion.z);
    if (speed <= STOP_EPS) return new float[]{0, 0};
    double rad = Math.toRadians(yaw);
    double forward = -motion.x * Math.sin(rad) + motion.z * Math.cos(rad);
    double strafe = motion.x * Math.cos(rad) + motion.z * Math.sin(rad);
    double threshold = Math.max(0.02, moveAttribute * 0.98) * 1.25;
    if (Math.max(Math.abs(forward), Math.abs(strafe)) < threshold) return new float[]{0, 0};
    if (Math.abs(forward) >= Math.abs(strafe)) return new float[]{forward > 0 ? -1 : 1, 0};
    return new float[]{0, strafe > 0 ? -1 : 1};
}

float[] getRotations(float lastYaw, float lastPitch) {
    if (!locked || disturbed || useConflict || !usable()
        || keybinds.isMouseDown(1) || inventory.getSlot() != fireSlot
        || target == null || !validTarget(target) || !movementReady(client.getPlayer())) return null;
    // BedDefender's silent method: smooth from the last observed normal look
    // using shortest-path yaw, then return the result through getRotations.
    // Never touch the visible camera or synthesize a rotation packet.
    if (plannedTick != tick) {
        plannedTick = tick;
        motionAim = solve(target, eye(client.getPlayer()), client.time());
        aim = motionAim;
        if (aim != null) {
            show(aim);
            float targetYaw = observedYaw + (float) wrap(aim.yaw - observedYaw);
            float[] step = smoothRotation(observedYaw, observedPitch, targetYaw, aim.pitch);
            offeredYaw = step[0];
            offeredPitch = step[1];
        }
        else display = null;
    }
    if (motionAim == null) return null;
    return new float[]{offeredYaw, offeredPitch};
}

float[] smoothRotation(float currentYaw, float currentPitch, float targetYaw, float targetPitch) {
    // Same per-axis step rule as the current BedDefender smoothRotation.
    float aimSpeed = (float) Math.max(1, Math.min(180, option("Aim Speed")));
    float diffYaw = targetYaw - currentYaw;
    float diffPitch = targetPitch - currentPitch;
    return new float[]{currentYaw + Math.max(-aimSpeed, Math.min(aimSpeed, diffYaw)),
        currentPitch + Math.max(-aimSpeed, Math.min(aimSpeed, diffPitch))};
}

boolean lookingAt(Intercept shot) {
    // Outgoing normal C05/C06 observation only, not server ACK. No packet-count
    // or onGround requirement: normal airborne/Stasis updates remain valid.
    return shot != null && plannedTick == tick
        && Math.abs(wrap(offeredYaw - shot.yaw)) < 0.001
        && Math.abs(offeredPitch - shot.pitch) < 0.001
        && Math.abs(wrap(observedYaw - shot.yaw)) < 0.001
        && Math.abs(observedPitch - shot.pitch) < 0.001;
}

void onPostMotion() {
    if (!locked) return;
    if (disturbed) { finish("Cancelled: server movement update", true); return; }
    if (useConflict || keybinds.isMouseDown(1)) {
        finish("Cancelled: manual / competing item use", true);
        return;
    }
    if (!usable()) { finish("Cancelled: player unavailable", true); return; }
    Entity player = client.getPlayer();
    long now = client.time();
    if (!canInterceptFrom(player)) {
        finish("Cancelled: airborne without Stasis / unsupported movement", true);
        return;
    }
    if (client.time() - lockedAt >= option("Lock timeout")) {
        finish("Cancelled: timeout", true);
        return;
    }
    // PearlFireball's use phase: wait for selection time + a later tick, then
    // require local slot and observed outgoing C09 slot to agree.
    if (now < nextUseAttemptAt || tick <= selectedTick) return;
    if (!movementReady(player)) return;
    if (target == null || !validTarget(target) || world.getEntityById(target.id) == null) {
        finish("Cancelled: pearl gone", true);
        return;
    }
    if (inventory.getSlot() != fireSlot || !isFireball(inventory.getStackInSlot(fireSlot))) {
        finish("Cancelled: held item changed", true);
        return;
    }
    if (observedSlot != fireSlot || !lookingAt(motionAim)) return;
    Intercept shot = motionAim;
    // The Stasis switch also bypasses this late position-drift movement gate.
    // Keep the current-tick aim, age and obstruction checks; no forced values.
    if (client.time() - shot.computedAt > 50
        || (!stasisActive() && distance(eye(player), shot.origin) > STOP_EPS)) return;
    // Recheck geometry, but keep the angle already used by the normal update.
    clearanceCache.clear();
    if (client.raycastBlock(distance(shot.origin, shot.point), shot.yaw, shot.pitch) != null
        || !clearLine(shot.origin, shot.point, 0.3)) return;
    try {
        if (disturbed || useConflict || !movementReady(player)
            || inventory.getSlot() != fireSlot || client.time() - shot.computedAt > 50) return;
        ItemStack held = inventory.getStackInSlot(fireSlot);
        if (!isFireball(held)) { finish("Cancelled: fireball unavailable", true); return; }
        target.handled = true; // Interceptor safeguard: do not blindly retry a use.
        attemptedUse = true;
        usePacketObserved = false;
        usePermit = true; // Consumed once by our ordinary outgoing packet callback.
        // Retain this script's existing standard right-click-in-air packet path.
        // This is not a raw mouse click and never bypasses the packet event.
        client.sendPacket(new C08(held));
        attemptedUse = false;
        finish(usePacketObserved ? "Fireball use observed (unconfirmed)"
            : "Use callback missing / blocked; not retried", true);
    } catch (Exception ex) {
        finish("Cancelled: packet error", true);
    } finally {
        usePermit = false;
        attemptedUse = false;
    }
}

boolean onPacketSent(CPacket packet) {
    // These are observations BEFORE sending, never server acknowledgements.
    // A module cancelling/delaying packets AFTER this callback is not detectable
    // with this API. Do not combine with packet buffering or competing aim modules.
    if (packet instanceof C09) observedSlot = ((C09) packet).slot;
    // BedDefender observes normal C05/C06 angles. rotating is the documented
    // wrapper flag for those packets; no packet field is modified here.
    if (packet instanceof C03 && ((C03) packet).rotating) {
        observedYaw = ((C03) packet).yaw;
        observedPitch = ((C03) packet).pitch;
    }
    if (enabled && packet instanceof C08 && (locked || tick <= blockUseThroughTick)) {
        ItemStack packetItem = ((C08) packet).itemStack;
        // Same null-wrapper fallback as PearlFireball. Observe the attempted
        // item, not an acknowledgement from the server.
        if (packetItem == null && inventory.getSlot() == fireSlot && observedSlot == fireSlot)
            packetItem = inventory.getStackInSlot(fireSlot);
        boolean allowed = locked && attemptedUse && usePermit && !disturbed && !useConflict && usable()
            && !keybinds.isMouseDown(1) && lookingAt(motionAim)
            && movementReady(client.getPlayer()) && inventory.getSlot() == fireSlot
            && observedSlot == fireSlot && isFireball(packetItem)
            && isFireball(inventory.getStackInSlot(fireSlot));
        usePermit = false;
        if (!allowed) {
            if (locked) useConflict = true;
            return false; // Includes held right-click / use from another module.
        }
        usePacketObserved = true;
    }
    if (packet instanceof C08 && enabled && world.exists() && client.getPlayer() != null) {
        ItemStack held = ((C08) packet).itemStack;
        // C08(ItemStack) wrappers can have a null public itemStack field.
        if (held == null) held = inventory.getStackInSlot(inventory.getSlot());
        if (held != null && itemName(held).contains("enderpearl")) {
            ownThrowAt = client.time();
            ownThrowEye = eye(client.getPlayer());
        }
    }
    return true;
}

boolean onMouse(int button, boolean state, int mouseX, int mouseY) {
    if (enabled && button == 1 && state && (locked || tick <= blockUseThroughTick)) {
        if (locked) useConflict = true;
        return false;
    }
    return true; // Always allow release; never leave a key artificially held.
}

boolean onPacketReceived(SPacket packet) {
    // Only set a flag here; callbacks may run before processing/on a network thread.
    if (locked && (packet instanceof S08 || packet instanceof S09 || packet instanceof S27
        || (packet instanceof S12 && ((S12) packet).entityId == localPlayerId))) disturbed = true;
    return true;
}

void finish(String message, boolean handled) {
    if (target != null && handled) target.handled = true;
    boolean wasLocked = locked;
    locked = false;
    usePermit = false;
    attemptedUse = false;
    useConflict = false;
    motionAim = null;
    plannedTick = -1;
    aim = null;
    target = null;
    stableTicks = brakeTicks = 0;
    status = message;
    if (wasLocked) {
        nextTriggerAt = client.time() + 300;
        blockUseThroughTick = tick; // No second use later in this same client tick.
    }
    // Called AFTER the ordinary use call returns, or on any cancellation.
    // This is local use completion, not a server acknowledgement. Never switch
    // off the player's pre-existing Stasis, and never write player motion.
    releaseAutoStasis();
    // Releasing input means ceasing overrides; never replay stale key states.
    if (wasLocked) restoreOriginalSlot();
    oldSlot = -1;
    fireSlot = -1;
    selectedAt = nextUseAttemptAt = 0;
}

void restoreOriginalSlot() {
    // PearlFireball's restore API, plus the interceptor's manual-slot safeguard.
    if (!button("Restore slot") || oldSlot < 0 || oldSlot > 8
        || !world.exists() || client.getPlayer() == null) return;
    if (inventory.getSlot() == oldSlot || inventory.getSlot() != fireSlot) return;
    inventory.setSlot(oldSlot);
}

Intercept solve(PearlTrack p, Vec3 origin, long now) {
    if (p.velocity == null) return null;
    clearanceCache.clear();
    // This is the user-configured LOCAL RTT, not an automatic measurement.
    // Read it for every solve, including the plan offered to normal movement.
    pingMs = configuredPing();
    // Default 1x models
    // local inbound observation age (~RTT/2) + outbound use delivery (~RTT/2).
    // Do not add the thrower's RTT: the pearl already exists on the server.
    double lead = (Math.max(0, now - p.sampledAt) + pingMs * option("Own ping lead factor")
        + option("Extra lead")) / 50.0;
    boolean vanilla = button("Vanilla fireball");
    double speed = option(vanilla ? "Fireball initial speed" : "Fireball speed");
    double acceleration = vanilla ? option("Fireball acceleration") : 0;
    double fireDrag = vanilla ? option("Fireball drag") : 1;
    double offset = option("Spawn offset");
    double drag = option("Pearl drag"), gravity = option("Pearl gravity");
    double flight = interceptTime(p.pos, p.velocity, origin, lead, speed, acceleration, fireDrag, offset,
        drag, gravity, option("Max flight"));
    if (flight < 0) return null;
    Vec3 basePoint = predict(p.pos, p.velocity, lead + flight, drag, gravity);
    double calibration = option("Prediction factor");
    double total = lead + flight + calibration;
    if (total < 0) return null;
    // Deliberate manual bias ALONG the curved pearl path, not yaw/pitch offsets.
    // Do not re-solve the unbiased equation and accidentally erase calibration.
    Vec3 point = predict(p.pos, p.velocity, total, drag, gravity);
    float[] rot = angles(origin, point);
    double dist = distance(origin, point);
    if (dist < 2 || dist > 128 || point.y < 0 || point.y > 255) return null;
    double actualFlight = fireballFlightTime(dist - offset, speed, acceleration, fireDrag, option("Max flight"));
    if (actualFlight < 0) return null;
    // Exact local-eye ray blocks shots through solid geometry. A conservative
    // corridor test below also covers projectile radius and pearl's future arc.
    if (client.raycastBlock(dist, rot[0], rot[1]) != null) return null;
    if (!clearLine(origin, point, 0.3)) return null;
    Vec3 prior = p.pos;
    for (double t = 0.25; t < total + 0.25; t += 0.25) {
        Vec3 next = predict(p.pos, p.velocity, Math.min(t, total), drag, gravity);
        if (!clearLine(prior, next, 0.125)) return null;
        prior = next;
    }
    Intercept result = new Intercept();
    result.origin = origin;
    result.point = point;
    result.basePoint = basePoint;
    result.yaw = rot[0];
    result.pitch = rot[1];
    result.flightTicks = actualFlight;
    result.leadTicks = lead;
    result.calibrationTicks = calibration;
    result.computedAt = now;
    return result;
}

// Discrete vanilla-style recurrence: position += v; v *= drag; v.y -= gravity.
// Fractional tick interpolation keeps the root solver continuous.
Vec3 predict(Vec3 pos, Vec3 velocity, double ticks, double drag, double gravity) {
    int n = (int) Math.floor(Math.max(0, ticks));
    double f = Math.max(0, ticks) - n;
    double power = Math.pow(drag, n);
    double sum = Math.abs(1 - drag) < 1.0e-9 ? n : (1 - power) / (1 - drag);
    double fall = Math.abs(1 - drag) < 1.0e-9 ? n * (n - 1) * 0.5 : (n - sum) / (1 - drag);
    return new Vec3(pos.x + velocity.x * (sum + f * power),
        pos.y + velocity.y * (sum + f * power) - gravity * (fall + f * sum),
        pos.z + velocity.z * (sum + f * power));
}

// Minecraft 1.8.9 EntityFireball in air, acceleration aligned to launch direction:
// position += velocity; velocity = (velocity + acceleration) * drag.
// Defaults v0=0, a=0.1, drag=0.95. Constant mode is a=0, drag=1.
double fireballDistance(double ticks, double initial, double acceleration, double drag) {
    double t = Math.max(0, ticks);
    int n = (int) Math.floor(t);
    double f = t - n;
    if (Math.abs(1 - drag) < 1.0e-9)
        return n * initial + acceleration * n * (n - 1) * 0.5 + f * (initial + acceleration * n);
    double terminal = acceleration * drag / (1 - drag);
    double power = Math.pow(drag, n);
    double sum = (1 - power) / (1 - drag);
    return terminal * n + (initial - terminal) * sum
        + f * (terminal + (initial - terminal) * power);
}

double fireballFlightTime(double distance, double initial, double acceleration, double drag, double horizon) {
    if (distance < 0 || horizon <= 0 || fireballDistance(horizon, initial, acceleration, drag) < distance) return -1;
    if (distance == 0) return 0;
    double lo = 0, hi = horizon;
    for (int i = 0; i < 32; i++) {
        double mid = (lo + hi) * 0.5;
        if (fireballDistance(mid, initial, acceleration, drag) < distance) lo = mid;
        else hi = mid;
    }
    return (lo + hi) * 0.5;
}

double interceptTime(Vec3 pos, Vec3 velocity, Vec3 origin, double lead,
    double speed, double acceleration, double fireDrag, double offset, double drag, double gravity, double horizon) {
    if ((speed <= 0 && acceleration <= 0) || lead < 0) return -1;
    double left = 0;
    double previous = distance(origin, predict(pos, velocity, lead, drag, gravity)) - offset;
    if (previous <= 0) return -1;
    for (double right = 0.125; right <= horizon + 1.0e-6; right += 0.125) {
        double value = distance(origin, predict(pos, velocity, lead + right, drag, gravity))
            - offset - fireballDistance(right, speed, acceleration, fireDrag);
        if (value <= 0) {
            double lo = left, hi = right;
            for (int i = 0; i < 24; i++) {
                double mid = (lo + hi) * 0.5;
                double gap = distance(origin, predict(pos, velocity, lead + mid, drag, gravity))
                    - offset - fireballDistance(mid, speed, acceleration, fireDrag);
                if (gap > 0) lo = mid; else hi = mid;
            }
            return (lo + hi) * 0.5;
        }
        left = right;
    }
    return -1;
}

boolean clearLine(Vec3 from, Vec3 to, double radius) {
    int steps = Math.max(1, (int) Math.ceil(distance(from, to) / 0.15));
    if (steps > 900) return false;
    for (int i = 0; i <= steps; i++) {
        double f = i / (double) steps;
        double x = from.x + (to.x - from.x) * f;
        double y = from.y + (to.y - from.y) * f;
        double z = from.z + (to.z - from.z) * f;
        for (int bx = (int) Math.floor(x - radius); bx <= (int) Math.floor(x + radius); bx++)
            for (int by = (int) Math.floor(y - radius); by <= (int) Math.floor(y + radius); by++)
                for (int bz = (int) Math.floor(z - radius); bz <= (int) Math.floor(z + radius); bz++) {
                    // API lacks full collision boxes: reject non-air voxels.
                    // This intentionally also rejects water and narrow gaps.
                    String key = bx + "," + by + "," + bz;
                    Boolean clear = clearanceCache.get(key);
                    if (clear == null) {
                        Block block = world.getBlockAt(bx, by, bz);
                        clear = block != null && ("air".equals(block.name) || "minecraft:air".equals(block.name));
                        clearanceCache.put(key, clear);
                    }
                    if (!clear) return false;
                }
    }
    return true;
}

String itemName(ItemStack item) {
    return ((item.name == null ? "" : item.name) + " " + (item.type == null ? "" : item.type))
        .toLowerCase().replace("_", "");
}
boolean isFireball(ItemStack item) {
    if (item == null || item.stackSize <= 0) return false;
    String name = itemName(item);
    return name.contains("firecharge") || name.contains("fireball");
}
int findFireball() {
    for (int i = 0; i < 9; i++) if (isFireball(inventory.getStackInSlot(i))) return i;
    return -1;
}
Vec3 eye(Entity p) {
    Vec3 pos = p.getPosition();
    return new Vec3(pos.x, pos.y + p.getEyeHeight(), pos.z);
}
Vec3 copy(Vec3 v) { return new Vec3(v.x, v.y, v.z); }
Vec3 subtract(Vec3 a, Vec3 b) { return new Vec3(a.x-b.x, a.y-b.y, a.z-b.z); }
double norm(Vec3 v) { return Math.sqrt(v.x*v.x + v.y*v.y + v.z*v.z); }
double distance(Vec3 a, Vec3 b) { return norm(subtract(a,b)); }
double wrap(double angle) { return ((angle + 180) % 360 + 360) % 360 - 180; }
float[] angles(Vec3 from, Vec3 to) {
    Vec3 d = subtract(to, from);
    return new float[]{(float) wrap(Math.toDegrees(Math.atan2(d.z, d.x)) - 90),
        (float) -Math.toDegrees(Math.atan2(d.y, Math.hypot(d.x, d.z)))};
}
void show(Intercept prediction) { display = prediction; displayUntil = client.time() + 1200; }
String number(double value) {
    String text = "" + util.round(value, 2);
    if (text.indexOf('.') < 0) text += ".";
    while (text.length() - text.indexOf('.') - 1 < 2) text += "0";
    return text;
}
String bearing(float yaw) {
    String[] names = {"S (+Z)", "SW", "W (-X)", "NW", "N (-Z)", "NE", "E (+X)", "SE"};
    return names[((int) Math.floor((yaw + 360 + 22.5) / 45)) & 7];
}
void onRenderWorld(float partialTicks) {
    if (!enabled || !world.exists() || !button("Draw prediction") || display == null
        || client.time() > displayUntil) return;
    Vec3 p = display.point;
    int color = 0xFF48E5FF;
    render.line3D(new Vec3(p.x-.25,p.y,p.z), new Vec3(p.x+.25,p.y,p.z), 2, color);
    render.line3D(new Vec3(p.x,p.y-.25,p.z), new Vec3(p.x,p.y+.25,p.z), 2, color);
    render.line3D(new Vec3(p.x,p.y,p.z-.25), new Vec3(p.x,p.y,p.z+.25), 2, color);
    render.line3D(display.origin, p, 1, color);
    if (Math.abs(display.calibrationTicks) > 0.001) {
        Vec3 b = display.basePoint;
        render.line3D(new Vec3(b.x-.15,b.y,b.z), new Vec3(b.x+.15,b.y,b.z), 1, 0xFFAAAAAA);
        render.line3D(b, p, 1, 0xFFAAAAAA);
    }
    render.tracer(p, 1, color, partialTicks);
}
void onRenderTick(float partialTicks) {
    if (!enabled || !world.exists() || !button("Draw prediction")) return;
    render.text("PearlInterceptor | " + status, 8, 8, 1, 0xFFFFFFFF, true);
    if (display == null || client.time() > displayUntil) return;
    Vec3 p = display.point;
    render.text("THEORY XYZ: " + number(p.x) + ", " + number(p.y) + ", " + number(p.z),
        8, 20, 1, 0xFF48E5FF, true);
    render.text(bearing(display.yaw) + " | yaw " + number(display.yaw) + " pitch "
        + number(display.pitch) + " | flight " + number(display.flightTicks * 50) + " ms",
        8, 32, 1, 0xFF48E5FF, true);
    render.text("Manual ping " + pingMs + " ms RTT | model lead " + number(display.leadTicks * 50) + " ms",
        8, 44, 1, 0xFFBBBBBB, true);
    render.text("Prediction factor " + number(display.calibrationTicks) + " ticks ("
        + number(display.calibrationTicks * 50) + " ms along pearl path)",
        8, 56, 1, 0xFFBBBBBB, true);
    render.text(button("Vanilla fireball") ? "Fireball: 1.8.9 acceleration model" : "Fireball: constant-speed model",
        8, 68, 1, 0xFFBBBBBB, true);
}
