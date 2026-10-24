package com.sengine.engine.ecs

// ─── Entity Component System (ECS) ───────────────────────────────────────

/**
 * Data-oriented Entity Component System for high-performance gameplay logic.
 * Complements the existing GameObject hierarchy for use cases where
 * raw iteration speed matters (e.g., bullet hell, particle sim).
 */

typealias EntityId = Long

/** Component storage — archetype-based for fast iteration. */
class ComponentStore<T> {
    private val data = HashMap<EntityId, T>()

    fun set(entity: EntityId, component: T) { data[entity] = component }
    fun get(entity: EntityId): T? = data[entity]
    fun remove(entity: EntityId) { data.remove(entity) }
    fun has(entity: EntityId): Boolean = entity in data
    fun all(): List<Pair<EntityId, T>> = data.entries.map { it.key to it.value }
    fun entities(): Set<EntityId> = data.keys
    val size: Int get() = data.size
    fun clear() { data.clear() }
}

/** Tag component — marks entities with a label. */
class TagStore {
    private val tags = HashMap<EntityId, MutableSet<String>>()

    fun addTag(entity: EntityId, tag: String) {
        tags.getOrPut(entity) { mutableSetOf() }.add(tag)
    }

    fun removeTag(entity: EntityId, tag: String) {
        tags[entity]?.remove(tag)
    }

    fun hasTag(entity: EntityId, tag: String): Boolean = tags[entity]?.contains(tag) ?: false

    fun getEntitiesWithTag(tag: String): Set<EntityId> {
        return tags.entries.filter { tag in it.value }.map { it.key }.toSet()
    }
}

/** ECS World — manages entities and component stores. */
class EcsWorld {
    private var nextEntityId: Long = 1L
    private val alive = mutableSetOf<EntityId>()
    private val stores = HashMap<String, ComponentStore<*>>()
    val tagStore = TagStore()

    /** Create a new entity. */
    fun createEntity(): EntityId {
        val id = nextEntityId++
        alive.add(id)
        return id
    }

    /** Destroy an entity and remove all its components. */
    fun destroyEntity(entity: EntityId) {
        alive.remove(entity)
        for (store in stores.values) store.remove(entity)
    }

    fun isAlive(entity: EntityId): Boolean = entity in alive

    /** Add a component to an entity. */
    @Suppress("UNCHECKED_CAST")
    fun <T> addComponent(entity: EntityId, componentClass: String, component: T) {
        val store = stores.getOrPut(componentClass) { ComponentStore<T>() } as ComponentStore<T>
        store.set(entity, component)
    }

    /** Get a component from an entity. */
    @Suppress("UNCHECKED_CAST")
    fun <T> getComponent(entity: EntityId, componentClass: String): T? {
        return (stores[componentClass] as? ComponentStore<T>)?.get(entity)
    }

    /** Check if entity has a component. */
    fun hasComponent(entity: EntityId, componentClass: String): Boolean {
        return stores[componentClass]?.has(entity) ?: false
    }

    /** Remove a component from an entity. */
    fun removeComponent(entity: EntityId, componentClass: String) {
        stores[componentClass]?.remove(entity)
    }

    /** Iterate all entities with a specific component. */
    @Suppress("UNCHECKED_CAST")
    fun <T> query(componentClass: String): List<Pair<EntityId, T>> {
        return (stores[componentClass] as? ComponentStore<T>)?.all() ?: emptyList()
    }

    /** Query entities with multiple components. */
    fun queryMultiple(vararg componentClasses: String): Set<EntityId> {
        if (componentClasses.isEmpty()) return alive.toSet()
        var result: Set<EntityId>? = null
        for (cls in componentClasses) {
            val entities = stores[cls]?.entities() ?: return emptySet()
            result = if (result == null) entities.toSet() else result.intersect(entities)
        }
        return result ?: emptySet()
    }

    /** Get all entity IDs. */
    fun allEntities(): Set<EntityId> = alive.toSet()

    val entityCount: Int get() = alive.size

    fun clear() {
        alive.clear()
        stores.values.forEach { it.clear() }
        nextEntityId = 1L
    }
}

// ─── Common ECS Components ───────────────────────────────────────────────

/** Transform data for ECS entities. */
data class TransformData(
    var x: Float = 0f,
    var y: Float = 0f,
    var rotation: Float = 0f,
    var scaleX: Float = 1f,
    var scaleY: Float = 1f,
)

/** Velocity for physics/movement. */
data class VelocityData(
    var vx: Float = 0f,
    var vy: Float = 0f,
    var angularVelocity: Float = 0f,
)

/** Health/HP component. */
data class HealthData(
    var current: Float = 100f,
    var max: Float = 100f,
    var regeneration: Float = 0f,
    var invincible: Boolean = false,
) {
    val isDead: Boolean get() = current <= 0f
    val ratio: Float get() = if (max > 0f) current / max else 0f

    fun damage(amount: Float) {
        if (!invincible) current = (current - amount).coerceAtLeast(0f)
    }

    fun heal(amount: Float) {
        current = (current + amount).coerceAtMost(max)
    }

    fun update(dt: Float) {
        if (regeneration > 0f && current < max) {
            current = (current + regeneration * dt).coerceAtMost(max)
        }
    }
}

/** Damage component — applied temporarily. */
data class DamageData(
    var amount: Float = 10f,
    var source: EntityId = 0L,
    var type: Int = 0, // 0=Physical, 1=Fire, 2=Ice, 3=Lightning, 4=Poison
)

/** Sprite reference for ECS rendering. */
data class SpriteData(
    var texture: String = "",
    var color: Int = 0xFFFFFFFF.toInt(),
    var width: Float = 1f,
    var height: Float = 1f,
    var flipX: Boolean = false,
    var flipY: Boolean = false,
    var sortingOrder: Int = 0,
)

/** AI state for ECS entities. */
data class AiStateData(
    var state: Int = 0, // 0=Idle, 1=Patrol, 2=Chase, 3=Attack, 4=Flee, 5=Dead
    var target: EntityId = 0L,
    var alertRadius: Float = 8f,
    var attackRadius: Float = 2f,
    var stateTimer: Float = 0f,
)
