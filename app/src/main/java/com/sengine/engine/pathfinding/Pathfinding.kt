package com.sengine.engine.pathfinding

import com.sengine.engine.core.Component
import com.sengine.engine.core.Prop
import kotlin.math.*

// ─── A* Pathfinding System ────────────────────────────────────────────────

/** A node in the navigation grid. */
class NavNode(
    val x: Int,
    val y: Int,
    var walkable: Boolean = true,
    var cost: Float = 1f,          // Movement cost multiplier
    var gScore: Float = Float.MAX_VALUE,
    var fScore: Float = Float.MAX_VALUE,
    var parent: NavNode? = null,
) {
    fun heuristic(other: NavNode): Float {
        val dx = abs(x - other.x).toFloat()
        val dy = abs(y - other.y).toFloat()
        // Octile distance (supports diagonal movement)
        return max(dx, dy) + (sqrt(2f) - 1f) * min(dx, dy)
    }
}

/** A navigation grid for A* pathfinding. */
class NavGrid(
    val width: Int,
    val height: Int,
    val cellSize: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    val nodes: Array<Array<NavNode>> = Array(width) { x ->
        Array(height) { y -> NavNode(x, y) }
    }

    /** Set walkability for a cell. */
    fun setWalkable(x: Int, y: Int, walkable: Boolean) {
        if (x in 0 until width && y in 0 until height) {
            nodes[x][y].walkable = walkable
        }
    }

    /** Set movement cost for a cell. */
    fun setCost(x: Int, y: Int, cost: Float) {
        if (x in 0 until width && y in 0 until height) {
            nodes[x][y].cost = cost
        }
    }

    /** Convert world position to grid coordinates. */
    fun worldToGrid(wx: Float, wy: Float): Pair<Int, Int> {
        val gx = ((wx - offsetX) / cellSize).toInt()
        val gy = ((wy - offsetY) / cellSize).toInt()
        return gx.coerceIn(0, width - 1) to gy.coerceIn(0, height - 1)
    }

    /** Convert grid coordinates to world position (center of cell). */
    fun gridToWorld(gx: Int, gy: Int): Pair<Float, Float> {
        val wx = offsetX + (gx + 0.5f) * cellSize
        val wy = offsetY + (gy + 0.5f) * cellSize
        return wx to wy
    }

    /** Get neighbors of a node (8-directional with diagonal support). */
    fun getNeighbors(node: NavNode): List<NavNode> {
        val neighbors = mutableListOf<NavNode>()
        for (dx in -1..1) {
            for (dy in -1..1) {
                if (dx == 0 && dy == 0) continue
                val nx = node.x + dx
                val ny = node.y + dy
                if (nx in 0 until width && ny in 0 until height) {
                    val neighbor = nodes[nx][ny]
                    if (neighbor.walkable) {
                        // Prevent diagonal cutting through walls
                        if (dx != 0 && dy != 0) {
                            val adjX = nodes[node.x + dx][node.y]
                            val adjY = nodes[node.x][node.y + dy]
                            if (adjX.walkable && adjY.walkable) {
                                neighbors.add(neighbor)
                            }
                        } else {
                            neighbors.add(neighbor)
                        }
                    }
                }
            }
        }
        return neighbors
    }

    /** Find path using A* algorithm. */
    fun findPath(startX: Int, startY: Int, endX: Int, endY: Int): List<NavNode>? {
        if (startX !in 0 until width || startY !in 0 until height) return null
        if (endX !in 0 until width || endY !in 0 until height) return null
        if (!nodes[endX][endY].walkable) return null

        // Reset all nodes
        for (x in 0 until width) {
            for (y in 0 until height) {
                nodes[x][y].gScore = Float.MAX_VALUE
                nodes[x][y].fScore = Float.MAX_VALUE
                nodes[x][y].parent = null
            }
        }

        val start = nodes[startX][startY]
        val end = nodes[endX][endY]

        start.gScore = 0f
        start.fScore = start.heuristic(end)

        val openSet = mutableListOf(start)
        val closedSet = mutableSetOf<NavNode>()

        while (openSet.isNotEmpty()) {
            // Find node with lowest fScore
            val current = openSet.minByOrNull { it.fScore } ?: break
            openSet.remove(current)
            closedSet.add(current)

            if (current === end) {
                return reconstructPath(current)
            }

            for (neighbor in getNeighbors(current)) {
                if (neighbor in closedSet) continue

                val dx = abs(neighbor.x - current.x)
                val dy = abs(neighbor.y - current.y)
                val moveCost = if (dx + dy == 2) sqrt(2f) else 1f
                val tentativeG = current.gScore + moveCost * neighbor.cost

                if (tentativeG < neighbor.gScore) {
                    neighbor.parent = current
                    neighbor.gScore = tentativeG
                    neighbor.fScore = tentativeG + neighbor.heuristic(end)

                    if (neighbor !in openSet) {
                        openSet.add(neighbor)
                    }
                }
            }
        }

        return null // No path found
    }

    /** Reconstruct path from end node to start. */
    private fun reconstructPath(end: NavNode): List<NavNode> {
        val path = mutableListOf<NavNode>()
        var current: NavNode? = end
        while (current != null) {
            path.add(0, current)
            current = current.parent
        }
        return path
    }

    /** Generate a simple rectangular obstacle. */
    fun addRectObstacle(x: Int, y: Int, w: Int, h: Int) {
        for (dx in 0 until w) {
            for (dy in 0 until h) {
                setWalkable(x + dx, y + dy, false)
            }
        }
    }

    /** Clear all obstacles. */
    fun clearObstacles() {
        for (x in 0 until width) {
            for (y in 0 until height) {
                nodes[x][y].walkable = true
                nodes[x][y].cost = 1f
            }
        }
    }

    /** Smooth a path by removing unnecessary waypoints. */
    fun smoothPath(path: List<NavNode>): List<NavNode> {
        if (path.size <= 2) return path
        val smoothed = mutableListOf(path.first())
        var i = 1
        while (i < path.size - 1) {
            // Skip nodes that don't change direction significantly
            val prev = smoothed.last()
            val curr = path[i]
            val next = path[i + 1]
            val dx1 = curr.x - prev.x
            val dy1 = curr.y - prev.y
            val dx2 = next.x - curr.x
            val dy2 = next.y - curr.y
            val cross = dx1 * dy2 - dy1 * dx2
            if (cross != 0 || i == path.size - 2) {
                smoothed.add(curr)
            }
            i++
        }
        if (smoothed.last() !== path.last()) smoothed.add(path.last())
        return smoothed
    }
}

/** Component that enables AI pathfinding on a game object. */
class NavAgent : Component() {
    override val type = "NavAgent"
    var speed: Float = 3f
    var angularSpeed: Float = 360f
    var stoppingDistance: Float = 0.5f
    var acceleration: Float = 8f
    var autoRepath: Boolean = true
    var repathInterval: Float = 0.5f
    var avoidObstacles: Boolean = true
    var obstacleRadius: Float = 0.5f

    // Runtime
    var targetX: Float = 0f
    var targetY: Float = 0f
    var hasDestination: Boolean = false
    var currentPath: List<NavNode> = emptyList()
    var pathIndex: Int = 0
    var repathTimer: Float = 0f
    var isMoving: Boolean = false
    var remainingDistance: Float = 0f

    override fun props() = listOf(
        Prop.F("Speed", { speed }, { speed = it.coerceAtLeast(0.1f) }, 0.5f),
        Prop.F("Angular Speed", { angularSpeed }, { angularSpeed = it.coerceAtLeast(1f) }, 10f),
        Prop.F("Stopping Distance", { stoppingDistance }, { stoppingDistance = it.coerceAtLeast(0.05f) }, 0.1f),
        Prop.F("Acceleration", { acceleration }, { acceleration = it.coerceAtLeast(0.1f) }, 0.5f),
        Prop.B("Auto Repath", { autoRepath }, { autoRepath = it }),
        Prop.F("Repath Interval", { repathInterval }, { repathInterval = it.coerceAtLeast(0.1f) }, 0.1f),
        Prop.B("Avoid Obstacles", { avoidObstacles }, { avoidObstacles = it }),
        Prop.F("Obstacle Radius", { obstacleRadius }, { obstacleRadius = it.coerceAtLeast(0.1f) }, 0.1f),
    )

    /** Set destination for this agent. */
    fun setDestination(x: Float, y: Float) {
        targetX = x; targetY = y
        hasDestination = true
    }

    /** Stop movement. */
    fun stop() {
        hasDestination = false
        currentPath = emptyList()
        pathIndex = 0
        isMoving = false
    }
}

/** Component that defines a navigation surface. */
class NavSurface : Component() {
    override val type = "NavSurface"
    var gridWidth: Int = 50
    var gridHeight: Int = 50
    var cellSize: Float = 1f
    var offsetX: Float = -25f
    var offsetY: Float = -25f

    // Runtime
    @Transient var grid: NavGrid? = null

    override fun props() = listOf(
        Prop.I("Grid Width", { gridWidth }, { gridWidth = it.coerceIn(5, 500) }),
        Prop.I("Grid Height", { gridHeight }, { gridHeight = it.coerceIn(5, 500) }),
        Prop.F("Cell Size", { cellSize }, { cellSize = it.coerceAtLeast(0.1f) }, 0.5f),
        Prop.F("Offset X", { offsetX }, { offsetX = it }, 1f),
        Prop.F("Offset Y", { offsetY }, { offsetY = it }, 1f),
    )

    fun getOrCreateGrid(): NavGrid {
        if (grid == null) {
            grid = NavGrid(gridWidth, gridHeight, cellSize, offsetX, offsetY)
        }
        return grid!!
    }
}

// ─── Flee / Pursue / Wander Behaviors ────────────────────────────────────

/** Simple AI behavior helpers. */
object AIBehaviors {
    /** Wander behavior — random direction changes. */
    fun wander(
        currentX: Float, currentY: Float,
        wanderRadius: Float, wanderJitter: Float,
        lastDirection: Float, dt: Float
    ): Triple<Float, Float, Float> {
        val newDir = lastDirection + (Math.random() * 2 - 1) * wanderJitter * dt
        val targetX = currentX + cos(newDir) * wanderRadius
        val targetY = currentY + sin(newDir) * wanderRadius
        return Triple(targetX.toFloat(), targetY.toFloat(), newDir.toFloat())
    }

    /** Pursue behavior — predict target position. */
    fun pursue(
        selfX: Float, selfY: Float,
        targetX: Float, targetY: Float,
        targetVx: Float, targetVy: Float,
        maxSpeed: Float
    ): Pair<Float, Float> {
        val dx = targetX - selfX
        val dy = targetY - selfY
        val dist = sqrt(dx * dx + dy * dy)
        val predictionTime = dist / maxSpeed
        val futureX = targetX + targetVx * predictionTime
        val futureY = targetY + targetVy * predictionTime
        return futureX to futureY
    }

    /** Flee behavior — move away from threat. */
    fun flee(
        selfX: Float, selfY: Float,
        threatX: Float, threatY: Float,
        fleeDistance: Float
    ): Pair<Float, Float> {
        val dx = selfX - threatX
        val dy = selfY - threatY
        val dist = sqrt(dx * dx + dy * dy).coerceAtLeast(0.001f)
        val nx = dx / dist
        val ny = dy / dist
        return (selfX + nx * fleeDistance) to (selfY + ny * fleeDistance)
    }

    /** Arrive behavior — slow down as approaching target. */
    fun arrive(
        selfX: Float, selfY: Float,
        targetX: Float, targetY: Float,
        maxSpeed: Float, slowRadius: Float
    ): Pair<Float, Float> {
        val dx = targetX - selfX
        val dy = targetY - selfY
        val dist = sqrt(dx * dx + dy * dy)
        val speed = if (dist < slowRadius) maxSpeed * (dist / slowRadius) else maxSpeed
        val nx = if (dist > 0.001f) dx / dist else 0f
        val ny = if (dist > 0.001f) dy / dist else 0f
        return (nx * speed) to (ny * speed)
    }
}
