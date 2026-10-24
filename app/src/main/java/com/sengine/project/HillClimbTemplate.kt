package com.sengine.project

import com.sengine.engine.core.AudioSource
import com.sengine.engine.core.Camera2D
import com.sengine.engine.core.Collider2D
import com.sengine.engine.core.GameObject
import com.sengine.engine.core.ParticleEmitter
import com.sengine.engine.core.Scene
import com.sengine.engine.core.ScriptComponent
import com.sengine.engine.core.SpriteRenderer
import com.sengine.engine.core.TextRenderer

/** Built-in endless off-road game template. Everything is authored with S Engine objects and scripts. */
object HillClimbTemplate {
    val template = Templates.Template(
        "Hill Climb Racer",
        "Spring-suspended off-road driving with four staged biomes, hazards, fuel, rotating challenges, garage upgrades and phone-tilt balance."
    ) { project ->
        project.controlScheme = "driveTilt"
        project.orientation = 0
        installAssets(project,
            "Off-Road Buggy", "Off-Road Wheel", "Gold Coin Icon", "Fuel Can Icon", "Sky Gradient",
            "Desert Sky", "Arctic Sky", "Moon Sky", "Mountains", "Cloud", "Coin Pickup", "Power Up", "Game Over", "Chiptune Loop")

        project.writeAsset("Game.js", GAME_SCRIPT)
        project.writeAsset("Vehicle.js", VEHICLE_SCRIPT)
        project.writeAsset("Pickup.js", PICKUP_SCRIPT)
        project.writeAsset("Hazard.js", HAZARD_SCRIPT)

        val scene = Scene("Race")
        scene.gravityY = 0f // The vehicle has a custom suspension model; terrain/pickups use engine colliders.

        val camera = obj(scene, "Main Camera", 0f, 0f)
        camera.add(Camera2D().also {
            it.size = 5.2f
            it.background = 0xFF82C8F3.toInt()
            it.follow = "Car"
            it.offsetX = 2.25f
            it.offsetY = 1.05f
            it.smoothing = 6.5f
        })
        obj(scene, "Sky", 0f, 0f, 42f, 24f, camera).also { it.order = -100 }
            .add(SpriteRenderer().also { it.texture = "Sky.png" })
        obj(scene, "Road Trip Music", 0f, 0f)
            .add(AudioSource().also { it.clip = "music_loop.wav"; it.loop = true; it.volume = 0.16f; it.playOnStart = true })

        // Reusable disabled prefabs. The world controller clones these ahead of the car and prunes them behind.
        obj(scene, "Dirt Tile", 0f, 0f, 1f, 16f).also {
            it.active = false; it.order = -12; it.tag = "Terrain"
        }.sprite(0xFF81502C).box()
        obj(scene, "Grass Tile", 0f, 0f, 1f, 0.15f).also {
            it.active = false; it.order = -11
        }.sprite(0xFF62C84A)

        val coin = obj(scene, "Coin Pickup", 0f, 0f, 0.46f, 0.46f).also {
            it.active = false; it.order = 5; it.tag = "Coin"
        }
        coin.add(SpriteRenderer().also { it.texture = "GoldCoin.png" })
        coin.add(Collider2D().also { it.shape = 1; it.radius = 0.48f; it.isTrigger = true })
        coin.script("Pickup.js")

        val fuel = obj(scene, "Fuel Can", 0f, 0f, 0.68f, 0.68f).also {
            it.active = false; it.order = 5; it.tag = "Fuel"
        }
        fuel.add(SpriteRenderer().also { it.texture = "FuelCan.png" })
        fuel.add(Collider2D().also { it.shape = 1; it.radius = 0.48f; it.isTrigger = true })
        fuel.script("Pickup.js")

        val rock = obj(scene, "Rock Hazard Prefab", 0f, 0f, 0.82f, 0.66f).also {
            it.active = false; it.order = 6; it.tag = "Hazard"
        }
        rock.add(SpriteRenderer().also { it.shape = 2; it.color = 0xFF786E62.toInt(); it.pivotY = 0.15f })
        rock.add(Collider2D().also { it.width = 0.84f; it.height = 0.66f; it.isTrigger = true })
        rock.script("Hazard.js")

        val cactus = obj(scene, "Cactus Hazard Prefab", 0f, 0f, 1f, 1f).also {
            it.active = false; it.order = 6; it.tag = "Hazard"
        }
        obj(scene, "Cactus Trunk", 0f, 0.62f, 0.28f, 1.35f, cactus).sprite(0xFF3D9955)
        obj(scene, "Cactus Left Arm", -0.27f, 0.66f, 0.40f, 0.17f, cactus).sprite(0xFF3D9955)
        obj(scene, "Cactus Left Rise", -0.44f, 0.85f, 0.16f, 0.48f, cactus).sprite(0xFF3D9955)
        obj(scene, "Cactus Right Arm", 0.28f, 0.90f, 0.38f, 0.17f, cactus).sprite(0xFF55AE65)
        obj(scene, "Cactus Right Rise", 0.44f, 1.08f, 0.16f, 0.44f, cactus).sprite(0xFF55AE65)
        cactus.add(Collider2D().also { it.width = 0.78f; it.height = 1.45f; it.offsetY = 0.72f; it.isTrigger = true })
        cactus.script("Hazard.js")

        val crash = obj(scene, "Crash Particles", 0f, 0f).also { it.order = 12 }
        crash.add(ParticleEmitter().also {
            it.emitting = false; it.rate = 0f; it.maxParticles = 160; it.speed = 3.4f; it.speedVariance = 0.55f
            it.direction = 90f; it.spread = 360f; it.lifetime = 0.85f; it.startSize = 0.22f; it.endSize = 0.025f
            it.sizeVariance = 0.45f; it.gravity = 1.6f; it.drag = 1.2f; it.rotationVariance = 180f
            it.rotationSpeedVariance = 220f; it.startColor = 0xFFFFB341.toInt(); it.endColor = 0x00E64A20
        })

        obj(scene, "Cloud Prefab", 0f, 0f, 3f, 1.55f).also {
            it.active = false; it.order = -60
        }.add(SpriteRenderer().also { it.texture = "Cloud.png" })

        obj(scene, "Far Hill", 0f, -2.3f, 18f, 5.3f).also {
            it.active = false; it.order = -80
        }.sprite(0xFF75B7A2, 2)
        obj(scene, "Near Hill", 0f, -2.55f, 14f, 4.1f).also {
            it.active = false; it.order = -75
        }.sprite(0xFF579B79, 2)

        val pine = obj(scene, "Pine Tree", 0f, 0f).also { it.active = false; it.order = -3 }
        obj(scene, "Tree Trunk", 0f, 0.35f, 0.2f, 0.75f, pine).sprite(0xFF765037)
        obj(scene, "Pine Lower", 0f, 0.83f, 1.05f, 1.05f, pine).sprite(0xFF2F8453, 2)
        obj(scene, "Pine Upper", 0f, 1.38f, 0.79f, 0.92f, pine).sprite(0xFF3A9B5E, 2)
        obj(scene, "Pine Tip", 0f, 1.86f, 0.5f, 0.68f, pine).sprite(0xFF55B66A, 2)

        // In-game HUD. Text and gauges are screen-space so they stay pinned during the camera chase.
        screenText(scene, "Brand", "HILL  /  CLIMB", 0f, 4.53f, 0.27f, 0xFF10243A, align = 1, order = 100)
        screenText(scene, "Stage Text", "COUNTRYSIDE", 0f, 4.12f, 0.22f, 0xFF31516A, align = 1, order = 100)
        screenText(scene, "Distance Text", "000 m", 0f, 3.44f, 0.37f, 0xFFFFFFFF, align = 1, order = 100)
        screenText(scene, "Challenge Text", "CHALLENGE  •  COLLECT 3 COINS", 0f, 2.94f, 0.18f, 0xFFFFE39B, align = 1, order = 100)
        screenText(scene, "Level Toast", "", 0f, 2.48f, 0.24f, 0xFFFFD45A, align = 1, order = 100)
        screenText(scene, "Best Text", "BEST  0 m", 6.95f, 4.38f, 0.22f, 0xFF28435B, align = 2, order = 100)
        screenText(scene, "Wallet Text", "COINS  0", 6.95f, 3.96f, 0.27f, 0xFFFFD45A, align = 2, order = 100)
        screenText(scene, "Fuel Label", "FUEL", -7.9f, 4.27f, 0.22f, 0xFFFFFFFF, align = 0, order = 100)
        obj(scene, "Fuel Track", -6.9f, 3.84f, 2.0f, 0.18f).also { it.order = 100 }
            .add(SpriteRenderer().also { it.color = 0xCC17283A.toInt(); it.screenSpace = true })
        obj(scene, "Fuel Fill", -6.9f, 3.84f, 1.92f, 0.10f).also { it.order = 101 }
            .add(SpriteRenderer().also { it.color = 0xFF64D991.toInt(); it.screenSpace = true })
        screenText(scene, "Fuel Percent", "100%", -5.65f, 3.83f, 0.20f, 0xFFFFFFFF, align = 0, order = 100)

        // A compact tachometer/speed readout anchors the bottom center of the driving HUD.
        obj(scene, "Tachometer Face", 0f, -2.55f, 1.22f, 1.22f).also { it.order = 100 }
            .add(SpriteRenderer().also { it.shape = 1; it.color = 0xCC10243A.toInt(); it.screenSpace = true })
        obj(scene, "Tachometer Ring", 0f, -2.55f, 1.14f, 1.14f).also { it.order = 101 }
            .add(SpriteRenderer().also { it.shape = 3; it.color = 0xFFFFC857.toInt(); it.screenSpace = true })
        obj(scene, "RPM Needle", 0f, -2.55f, 0.07f, 0.40f).also { it.order = 102 }
            .add(SpriteRenderer().also { it.color = 0xFFFF7043.toInt(); it.screenSpace = true })
        screenText(scene, "Speed Text", "0 km/h", 0f, -2.82f, 0.19f, 0xFFFFFFFF, align = 1, order = 103)
        screenText(scene, "Controls Hint", "GAS / BRAKE  •  TILT TO BALANCE", 0f, -1.63f, 0.17f, 0xCCFFFFFFL, align = 1, order = 100)

        val gameOverPanel = obj(scene, "Game Over Panel", 0f, 0f).also {
            it.active = false; it.order = 200
        }
        obj(scene, "Game Over Backdrop", 0f, 0f, 6.5f, 3.4f, gameOverPanel).also { it.order = 200 }
            .add(SpriteRenderer().also { it.color = 0xE817283A.toInt(); it.screenSpace = true })
        screenText(scene, "Game Over Title", "RUN OVER", 0f, 1.05f, 0.56f, 0xFFFFFFFF, 1, 201, gameOverPanel)
        screenText(scene, "Game Over Stats", "DISTANCE  0 m", 0f, 0.28f, 0.31f, 0xFFFFD45A, 1, 201, gameOverPanel)
        screenText(scene, "Game Over Bank", "WALLET  0 COINS", 0f, -0.30f, 0.25f, 0xFFB7D7F0, 1, 201, gameOverPanel)
        screenText(scene, "Game Over Tune", "NEXT  ENGINE  •  70 COINS", 0f, -0.75f, 0.20f, 0xFF8EE3B2, 1, 201, gameOverPanel)
        screenText(scene, "Game Over Hint", "TAP TO RUN AGAIN   /   TUNE TO UPGRADE", 0f, -1.25f, 0.17f, 0xFFFFFFFF, 1, 201, gameOverPanel)

        // Place the manager before the vehicle so all terrain and state are ready on the first frame.
        obj(scene, "Game Manager", 0f, 0f).script("Game.js")

        val rear = obj(scene, "Rear Wheel", -0.78f, -1.35f, 0.78f, 0.78f).also { it.order = 9 }
        rear.add(SpriteRenderer().also { it.texture = "OffroadWheel.png" })
        val front = obj(scene, "Front Wheel", 0.78f, -1.35f, 0.78f, 0.78f).also { it.order = 9 }
        front.add(SpriteRenderer().also { it.texture = "OffroadWheel.png" })

        val car = obj(scene, "Car", 0f, -0.8f, 2.55f, 1.42f).also {
            it.tag = "Player"; it.order = 10
        }
        car.add(SpriteRenderer().also { it.texture = "BuggyBody.png" })
        car.script("Vehicle.js")
        val sensor = obj(scene, "Pickup Sensor", 0.05f, 0.13f, 0.62f, 0.72f, car)
        sensor.tag = "Sensor"
        sensor.add(Collider2D().also { it.width = 1.15f; it.height = 0.85f; it.isTrigger = true })

        val exhaust = obj(scene, "Exhaust Smoke", -0.94f, -0.08f, 0.35f, 0.35f, car).also { it.order = 11 }
        exhaust.add(ParticleEmitter().also {
            it.direction = 180f; it.spread = 24f; it.speed = 0.75f; it.rate = 8f; it.lifetime = 0.7f
            it.startSize = 0.12f; it.endSize = 0.03f; it.gravity = -0.25f
            it.startColor = 0x99E9F1F5.toInt(); it.endColor = 0x00DDE7ED
        })

        project.saveScene(scene)
        project.startScene = "Race"
    }

    private fun installAssets(project: Project, vararg titles: String) {
        for (title in titles) {
            val item = AssetLibrary.items.firstOrNull { it.title == title } ?: continue
            // Bitmap asset generators are Android-only; script and scene tests still run headlessly.
            try { item.install(project) } catch (_: Throwable) { }
        }
    }

    private fun obj(scene: Scene, name: String, x: Float, y: Float, sx: Float = 1f, sy: Float = 1f, parent: GameObject? = null): GameObject {
        val go = scene.create(name, parent)
        go.x = x; go.y = y; go.scaleX = sx; go.scaleY = sy
        return go
    }

    private fun GameObject.sprite(color: Long, shape: Int = 0): GameObject {
        add(SpriteRenderer().also { it.color = color.toInt(); it.shape = shape })
        return this
    }

    private fun GameObject.box(trigger: Boolean = false): GameObject {
        add(Collider2D().also { it.isTrigger = trigger })
        return this
    }

    private fun GameObject.script(name: String): GameObject {
        add(ScriptComponent().also { it.script = name })
        return this
    }

    private fun screenText(
        scene: Scene, name: String, value: String, x: Float, y: Float, size: Float, color: Long,
        align: Int = 1, order: Int = 100, parent: GameObject? = null,
    ): GameObject {
        val go = obj(scene, name, x, y, parent = parent).also { it.order = order }
        go.add(TextRenderer().also {
            it.text = value; it.size = size; it.color = color.toInt(); it.bold = true
            it.align = align; it.screenSpace = true
        })
        return go
    }

    private const val GAME_SCRIPT = """
// Hill Climb Racer — endless terrain, four stage palettes, fuel, wallet and garage tuning.
var stepSize = 0.82;
var terrainCursor = -22;
var dirtTiles = [];
var grassTiles = [];
var scenery = [];
var car;
var camera;
var sky;
var runStartX = 0;
var startX = 0;
var nextCoinX = 7;
var nextFuelX = 54;
var nextHazardX = 34;
var nextCloudX = 10;
var nextHillX = -4;
var fuel = 100;
var wallet = 0;
var runCoins = 0;
var best = 0;
var distance = 0;
var speedKmh = 0;
var currentStage = -1;
var unlockedStage = 0;
var challengeIndex = 0;
var challengeValue = 0;
var airTime = 0;
var challengeDone = false;
var levelToastTimer = 0;
var running = true;
var gameOver = false;
var upgradeIndex = 0;
var engineLevel = 0;
var suspensionLevel = 0;
var tyreLevel = 0;
var fuelCapacity = 100;

function start() {
    car = scene.find("Car");
    camera = scene.find("Main Camera");
    sky = scene.find("Sky");
    wallet = Math.max(0, Number(storage.get("hcr.wallet", 0)) || 0);
    best = Math.max(0, Number(storage.get("hcr.best", 0)) || 0);
    engineLevel = Math.max(0, Number(storage.get("hcr.engine", 0)) || 0);
    suspensionLevel = Math.max(0, Number(storage.get("hcr.suspension", 0)) || 0);
    tyreLevel = Math.max(0, Number(storage.get("hcr.tyres", 0)) || 0);
    upgradeIndex = Math.floor(Math.max(0, Number(storage.get("hcr.upgradeIndex", 0)) || 0)) % 3;
    challengeIndex = Math.floor(Math.max(0, Number(storage.get("hcr.challengeIndex", 0)) || 0)) % 3;
    unlockedStage = Math.floor(Math.max(0, Number(storage.get("hcr.unlockedStage", 0)) || 0));
    runStartX = 0;
    startX = 0;
    fuel = fuelCapacity;
    applyStage(0);
    ensureWorld(0);
    updateChallenge();
    updateHud();
    log("Hill Climb Racer ready — gas and brake pedals; tilt your phone to balance in the air.");
}

function terrainHeight(x) {
    if (x < 2) return -1.72;
    var d = clamp((x - 2) / 260, 0, 1.25);
    return -1.72
        + (0.72 + d * 0.18) * Math.sin((x - 2) * 0.18)
        + (0.34 + d * 0.10) * Math.sin((x - 2) * 0.49 + 0.75)
        + (0.095 + d * 0.025) * Math.sin((x - 2) * 1.17 + 1.4);
}

function stageFor(x) {
    if (x < 44) return 0;
    if (x < 104) return 1;
    if (x < 178) return 2;
    return 3;
}
function stageName(i) { return ["COUNTRYSIDE", "SUNSET DESERT", "FROST RIDGE", "MOON VALLEY"][i]; }
function stageLabel(i) { return "LEVEL " + (i + 1) + " / 4  •  " + stageName(i); }
function stageDirt(i) { return ["#FF80502D", "#FF9A6037", "#FF607C91", "#FF4F4867"][i]; }
function stageGrass(i) { return ["#FF67C64B", "#FFFFC24A", "#FFE7F6F8", "#FF9582AE"][i]; }
function stageFar(i) { return ["#FF72B6A0", "#FFD89A74", "#FFA5D3E2", "#FF575B89"][i]; }
function stageNear(i) { return ["#FF559879", "#FFC57B53", "#FF78B6CC", "#FF41496D"][i]; }
function skyName(i) { return ["Sky.png", "DesertSky.png", "ArcticSky.png", "MoonSky.png"][i]; }

function completeStage(level) {
    if (level < 0 || level > 2) return;
    var key = "hcr.level" + level + ".cleared";
    if (storage.get(key, false)) return;
    var reward = [30, 45, 65][level];
    wallet += reward;
    storage.set(key, true);
    storage.set("hcr.wallet", wallet);
    audio.play("powerup.wav");
    log("LEVEL " + (level + 1) + " COMPLETE  •  GARAGE BONUS +" + reward);
    var toast = scene.find("Level Toast");
    if (toast) toast.text = "LEVEL " + (level + 1) + " CLEARED  •  +" + reward + " COINS";
    levelToastTimer = 3.8;
}

function applyStage(index) {
    if (index < 0 || index > 3 || currentStage == index) return;
    var previous = currentStage;
    currentStage = index;
    if (index > previous) for (var level = Math.max(0, previous); level < index && level < 3; level++) completeStage(level);
    if (index > unlockedStage) { unlockedStage = index; storage.set("hcr.unlockedStage", unlockedStage); }
    var dirt = scene.find("Dirt Tile"); if (dirt) dirt.color = stageDirt(index);
    var grass = scene.find("Grass Tile"); if (grass) grass.color = stageGrass(index);
    var far = scene.find("Far Hill"); if (far) far.color = stageFar(index);
    var near = scene.find("Near Hill"); if (near) near.color = stageNear(index);
    if (sky) sky.setTexture(skyName(index));
    for (var i = 0; i < dirtTiles.length; i++) if (dirtTiles[i].obj) dirtTiles[i].obj.color = stageDirt(index);
    for (var j = 0; j < grassTiles.length; j++) if (grassTiles[j].obj) grassTiles[j].obj.color = stageGrass(index);
    for (var k = 0; k < scenery.length; k++) {
        if (!scenery[k]) continue;
        if (scenery[k].name.indexOf("Far Hill") == 0) scenery[k].color = stageFar(index);
        if (scenery[k].name.indexOf("Near Hill") == 0) scenery[k].color = stageNear(index);
        if (scenery[k].name.indexOf("Pine Tree") == 0) scenery[k].active = index == 0 || index == 2;
    }
    var label = scene.find("Stage Text"); if (label) label.text = stageLabel(index);
}

function addTerrainSegment() {
    var x1 = terrainCursor;
    var x2 = x1 + stepSize;
    var y1 = terrainHeight(x1);
    var y2 = terrainHeight(x2);
    var dx = x2 - x1;
    var dy = y2 - y1;
    var len = Math.sqrt(dx * dx + dy * dy);
    var angle = Math.atan2(dy, dx);
    var midX = (x1 + x2) * 0.5;
    var midY = (y1 + y2) * 0.5;
    var depth = 16;
    var dirt = scene.spawn("Dirt Tile", midX + Math.sin(angle) * depth * 0.5, midY - Math.cos(angle) * depth * 0.5);
    if (dirt) {
        dirt.scaleX = len + 0.045; dirt.scaleY = depth; dirt.rotation = angle * 180 / Math.PI;
        dirt.color = stageDirt(currentStage); dirtTiles.push({ obj: dirt, x: x1 });
    }
    var top = scene.spawn("Grass Tile", midX - Math.sin(angle) * 0.065, midY + Math.cos(angle) * 0.065);
    if (top) {
        top.scaleX = len + 0.055; top.scaleY = 0.15; top.rotation = angle * 180 / Math.PI;
        top.color = stageGrass(currentStage); grassTiles.push({ obj: top, x: x1 });
    }
    terrainCursor = x2;
}

function spawnPickup(type, x, y) {
    var item = scene.spawn(type == "Fuel" ? "Fuel Can" : "Coin Pickup", x, y);
    if (item) {
        item.tag = type;
        if (type == "Coin") { item.scaleX = 0.46; item.scaleY = 0.46; }
        else { item.scaleX = 0.67; item.scaleY = 0.67; }
    }
}

function spawnCoinCluster(baseX) {
    var count = randomInt(4, 6);
    for (var i = 0; i < count; i++) {
        var x = baseX + i * 0.72;
        var y = terrainHeight(x) + 1.2 + Math.sin(i / Math.max(1, count - 1) * Math.PI) * 0.38;
        spawnPickup("Coin", x, y);
    }
}

function spawnHazard(atX) {
    var stage = stageFor(atX);
    var cactus = stage == 1;
    var item = scene.spawn(cactus ? "Cactus Hazard Prefab" : "Rock Hazard Prefab", atX,
        terrainHeight(atX) + (cactus ? 0.02 : 0.27));
    if (!item) return;
    item.tag = "Hazard";
    if (cactus) {
        var scale = random(0.78, 1.08);
        item.scaleX = scale; item.scaleY = scale;
    } else {
        item.scaleX = random(0.78, 1.18); item.scaleY = random(0.78, 1.12);
        item.color = ["#FF786E62", "#FFB97950", "#FFA5C1CF", "#FFAAA1C9"][stage];
        item.rotation = random(-12, 12);
    }
    scenery.push(item);
}

function ensureWorld(x) {
    while (terrainCursor < x + 34) addTerrainSegment();
    while (nextCoinX < x + 48) {
        spawnCoinCluster(nextCoinX);
        nextCoinX += random(13, 17);
    }
    while (nextFuelX < x + 50) {
        spawnPickup("Fuel", nextFuelX, terrainHeight(nextFuelX) + 1.15);
        nextFuelX += random(52, 76);
    }
    while (nextHazardX < x + 44) {
        spawnHazard(nextHazardX);
        var difficulty = stageFor(nextHazardX);
        nextHazardX += random(23, 31) - difficulty * 1.6;
    }
    while (nextCloudX < x + 50) {
        var cloud = scene.spawn("Cloud Prefab", nextCloudX, random(2.4, 4.4));
        if (cloud) { cloud.scaleX = random(2.4, 4.0); cloud.scaleY = random(0.8, 1.5); scenery.push(cloud); }
        nextCloudX += random(15, 23);
    }
    while (nextHillX < x + 48) {
        var hillStage = stageFor(nextHillX);
        var far = scene.spawn("Far Hill", nextHillX, random(-2.7, -1.7));
        if (far) { far.scaleX = random(15, 23); far.scaleY = random(4.4, 6.2); far.color = stageFar(hillStage); scenery.push(far); }
        var near = scene.spawn("Near Hill", nextHillX + 7, random(-3.2, -2.4));
        if (near) { near.scaleX = random(12, 18); near.scaleY = random(3.0, 4.8); near.color = stageNear(stageFor(nextHillX + 7)); scenery.push(near); }
        if (hillStage == 0 || hillStage == 2) {
            var tx = nextHillX + random(2, 10);
            var tree = scene.spawn("Pine Tree", tx, terrainHeight(tx));
            if (tree) { var scale = random(0.72, 1.25); tree.scaleX = scale; tree.scaleY = scale; scenery.push(tree); }
        }
        nextHillX += random(18, 25);
    }
}

function pruneWorld(x) {
    while (dirtTiles.length > 0 && dirtTiles[0].x < x - 24) { dirtTiles[0].obj.destroy(); dirtTiles.shift(); }
    while (grassTiles.length > 0 && grassTiles[0].x < x - 24) { grassTiles[0].obj.destroy(); grassTiles.shift(); }
    for (var k = scenery.length - 1; k >= 0; k--) {
        if (scenery[k] && scenery[k].x < x - 32) { scenery[k].destroy(); scenery.splice(k, 1); }
    }
    // Pickups well behind the chase camera can no longer be reached.
    var objects = scene.findAll("Coin");
    if (objects) for (var i = 0; i < objects.length; i++) if (objects[i].x < x - 22) objects[i].destroy();
    var cans = scene.findAll("Fuel");
    if (cans) for (var j = 0; j < cans.length; j++) if (cans[j].x < x - 22) cans[j].destroy();
}

function challengeLabel() {
    var names = ["COLLECT 3 COINS", "REACH 55 METRES", "AIRTIME 2.0 SECONDS"];
    return names[challengeIndex] || names[0];
}

function updateChallenge() {
    var targets = [3, 55, 2.0];
    var value = challengeIndex == 0 ? runCoins : challengeIndex == 1 ? distance : airTime;
    var target = targets[challengeIndex] || 3;
    if (!challengeDone && value >= target) {
        challengeDone = true;
        wallet += 25;
        storage.set("hcr.wallet", wallet);
        storage.set("hcr.challengeIndex", (challengeIndex + 1) % 3);
        audio.play("powerup.wav");
        log("CHALLENGE COMPLETE  •  GARAGE BONUS +25");
    }
    var label = scene.find("Challenge Text");
    if (label) {
        var progress = challengeIndex == 2 ? (Math.floor(airTime * 10) / 10).toFixed(1) : Math.floor(value);
        label.text = challengeDone ? "CHALLENGE COMPLETE  •  +25 COINS" : "CHALLENGE  •  " + challengeLabel() + "  " + progress + "/" + target;
        label.color = challengeDone ? "#FF8EE3B2" : "#FFFFE39B";
    }
}

function update(dt) {
    if (!car) return;
    var worldX = car.x;
    var stage = stageFor(worldX);
    applyStage(stage);
    ensureWorld(worldX);
    pruneWorld(worldX);
    if (levelToastTimer > 0) {
        levelToastTimer = Math.max(0, levelToastTimer - dt);
        if (levelToastTimer == 0) { var toast = scene.find("Level Toast"); if (toast) toast.text = ""; }
    }

    if (gameOver && (input.tapped || input.aDown || input.bDown)) startRun();
    if (gameOver && input.upgradeDown) buyUpgrade();

    if (running) {
        distance = Math.max(0, Math.floor(worldX - runStartX));
        if (distance > best) best = distance;
        updateChallenge();
        fuel = Math.max(0, fuel - dt * (2.9 + speedKmh * 0.022));
        if (fuel <= 0) endRun("OUT OF FUEL");
    }
    var zoom = 5.2 + Math.min(1.25, speedKmh / 115);
    if (camera) camera.size = zoom;
    updateHud();
}

function updateHud() {
    var d = scene.find("Distance Text"); if (d) d.text = ("0000" + distance).slice(-4) + " m";
    var b = scene.find("Best Text"); if (b) b.text = "BEST  " + best + " m";
    var w = scene.find("Wallet Text"); if (w) w.text = "COINS  " + wallet + "  +" + runCoins;
    var f = scene.find("Fuel Percent"); if (f) f.text = Math.ceil(fuel) + "%";
    var fill = scene.find("Fuel Fill");
    if (fill) {
        var width = 1.92 * clamp(fuel / fuelCapacity, 0, 1);
        fill.scaleX = Math.max(0.015, width);
        fill.x = -6.9 - 0.96 + width * 0.5;
        fill.color = fuel < 22 ? "#FFFF6258" : fuel < 48 ? "#FFFFC857" : "#FF64D991";
    }
    var speed = scene.find("Speed Text"); if (speed) speed.text = Math.floor(speedKmh) + " km/h";
    var needle = scene.find("RPM Needle"); if (needle) needle.rotation = -118 + clamp(speedKmh / 85, 0, 1) * 236;
    var tune = scene.find("Controls Hint");
    if (tune) tune.text = gameOver ? "TAP TO DRIVE AGAIN  •  TUNE TO UPGRADE" : "GAS / BRAKE  •  TILT TO BALANCE";
    var overTitle = scene.find("Game Over Title");
    var overStats = scene.find("Game Over Stats");
    var overWallet = scene.find("Game Over Bank");
    var overTune = scene.find("Game Over Tune");
    var panel = scene.find("Game Over Panel");
    if (overTitle && gameOver) overTitle.text = endReason == "OUT OF FUEL" ? "OUT OF FUEL" : endReason == "WRECKED" ? "WRECKED" : "RUN OVER";
    if (overStats && gameOver) overStats.text = "DISTANCE  " + distance + " m   •   BEST  " + best + " m";
    if (overWallet && gameOver) overWallet.text = "WALLET  " + wallet + " COINS   •   ENGINE " + engineLevel + " / GRIP " + tyreLevel;
    if (overTune && gameOver) overTune.text = "NEXT  " + getUpgradeName() + "  •  " + getUpgradeCost() + " COINS";
    if (panel) panel.active = gameOver;
}

function collect(kind) {
    if (!running || gameOver) return;
    if (kind == "Coin") {
        runCoins++;
        wallet += 5;
        storage.set("hcr.wallet", wallet);
        audio.play("coin.wav");
    } else if (kind == "Fuel") {
        fuel = Math.min(fuelCapacity, fuel + 38);
        audio.play("powerup.wav");
    }
    updateChallenge();
    updateHud();
}

function setSpeed(v) { speedKmh = Math.max(0, Number(v) * 3.6); }
function addAirTime(v) { if (running && !gameOver) airTime += Math.max(0, Number(v) || 0); }
function isRunning() { return running && !gameOver; }
function getStartX() { return startX; }
function getEngineLevel() { return engineLevel; }
function getSuspensionLevel() { return suspensionLevel; }
function getTyreLevel() { return tyreLevel; }
function gravityAt(x) { return stageFor(x) == 3 ? 3.6 : 9.81; }
function getUpgradeCost() {
    if (upgradeIndex == 0) return 70 + engineLevel * 55;
    if (upgradeIndex == 1) return 65 + suspensionLevel * 50;
    return 65 + tyreLevel * 50;
}
function getUpgradeName() { return ["ENGINE", "SUSPENSION", "TYRES"][upgradeIndex]; }

function buyUpgrade() {
    var cost = getUpgradeCost();
    if (wallet < cost) {
        log("Need " + (cost - wallet) + " more coins to tune " + getUpgradeName() + ".");
        audio.beep();
        return;
    }
    wallet -= cost;
    if (upgradeIndex == 0) engineLevel++;
    else if (upgradeIndex == 1) suspensionLevel++;
    else tyreLevel++;
    storage.set("hcr.wallet", wallet);
    storage.set("hcr.engine", engineLevel);
    storage.set("hcr.suspension", suspensionLevel);
    storage.set("hcr.tyres", tyreLevel);
    upgradeIndex = (upgradeIndex + 1) % 3;
    storage.set("hcr.upgradeIndex", upgradeIndex);
    audio.play("powerup.wav");
    log("Garage upgrade installed.");
}

function endRun(reason) {
    if (!running || gameOver) return;
    running = false;
    gameOver = true;
    endReason = reason || "CRASH";
    best = Math.max(best, distance);
    storage.set("hcr.best", best);
    audio.play("lose.wav");
    if (reason != "OUT OF FUEL") {
        var sparks = scene.find("Crash Particles");
        if (sparks && car) { sparks.x = car.x; sparks.y = car.y; sparks.burst(44); }
        scene.shake(0.34);
    }
    updateHud();
}

function startRun() {
    gameOver = false;
    running = true;
    endReason = "";
    runCoins = 0;
    airTime = 0;
    challengeDone = false;
    challengeIndex = Math.floor(Math.max(0, Number(storage.get("hcr.challengeIndex", challengeIndex)) || 0)) % 3;
    fuel = fuelCapacity;
    startX = Math.max(0, terrainCursor - 31);
    runStartX = startX;
    distance = 0;
    nextCoinX = Math.max(nextCoinX, startX + 6);
    nextFuelX = Math.max(nextFuelX, startX + 35);
    if (car) car.send("reset", startX);
    updateChallenge();
    updateHud();
}
"""

    private const val VEHICLE_SCRIPT = """
// Two-wheel spring chassis: gravity, damped suspension, slope traction, angular inertia and phone air-control.
var manager;
var rear;
var front;
var exhaust;
var x = 0;
var y = 0;
var vx = 0;
var vy = 0;
var angle = 0;
var angularVelocity = 0;
var wheelSpin = 0;
var grounded = false;
var rearCompression = 0;
var frontCompression = 0;
var halfWheelBase = 0.78;
var anchorDrop = 0.24;
var wheelRadius = 0.36;
var mass = 1.15;
var inertia = 1.9;

function start() {
    manager = scene.find("Game Manager");
    rear = scene.find("Rear Wheel");
    front = scene.find("Front Wheel");
    exhaust = scene.find("Exhaust Smoke");
    reset(Number(manager.send("getStartX")) || 0);
}

function reset(spawnX) {
    x = Number(spawnX) || 0;
    y = Number(manager.send("terrainHeight", x)) + 0.82;
    vx = 0; vy = 0; angle = 0; angularVelocity = 0; wheelSpin = 0;
    grounded = true; rearCompression = 0.08; frontCompression = 0.08;
    self.x = x; self.y = y; self.rotation = 0;
    placeWheels(0.08, 0.08, 0.3);
}

function groundAt(px) { return Number(manager.send("terrainHeight", px)); }
function clamp(v, lo, hi) { return Math.max(lo, Math.min(hi, v)); }

function placeWheels(rearComp, frontComp, rest) {
    var c = Math.cos(angle), s = Math.sin(angle);
    var rearAnchorX = x - c * halfWheelBase + s * anchorDrop;
    var frontAnchorX = x + c * halfWheelBase + s * anchorDrop;
    var rearAnchorY = y - s * halfWheelBase - c * anchorDrop;
    var frontAnchorY = y + s * halfWheelBase - c * anchorDrop;
    var rot = angle * 57.2957795 + wheelSpin;
    if (rear) { rear.x = rearAnchorX; rear.y = rearAnchorY - (rest - clamp(rearComp, -0.32, 0.24)); rear.rotation = rot; }
    if (front) { front.x = frontAnchorX; front.y = frontAnchorY - (rest - clamp(frontComp, -0.32, 0.24)); front.rotation = rot; }
}

function update(dt) {
    if (!manager || !manager.send("isRunning")) {
        if (exhaust) exhaust.setEmitting(false);
        if (manager) manager.send("setSpeed", 0);
        return;
    }
    dt = clamp(dt, 0, 0.05);
    var gas = input.a || input.axisX > 0.12;
    var brake = input.b || input.axisX < -0.12;
    var tilt = Number(input.tiltX) || 0;
    var engine = Number(manager.send("getEngineLevel")) || 0;
    var suspension = Number(manager.send("getSuspensionLevel")) || 0;
    var tyres = Number(manager.send("getTyreLevel")) || 0;
    var gravity = Math.max(0.5, Number(manager.send("gravityAt", x)) || 9.81);

    var rearX = x - halfWheelBase;
    var frontX = x + halfWheelBase;
    var rearGround = groundAt(rearX);
    var frontGround = groundAt(frontX);
    var slope = Math.atan2(frontGround - rearGround, halfWheelBase * 2);
    var rest = 0.30 + suspension * 0.025;
    var springK = gravity * (7.2 + suspension * 0.38);
    var damper = Math.sqrt(springK * mass * 0.5) * 0.78;

    // Each wheel applies its own damped spring force, allowing the chassis to pitch over uneven ground.
    var c = Math.cos(angle), s = Math.sin(angle);
    var rearAnchorY = y - s * halfWheelBase - c * anchorDrop;
    var frontAnchorY = y + s * halfWheelBase - c * anchorDrop;
    rearCompression = rearGround + wheelRadius + rest - rearAnchorY;
    frontCompression = frontGround + wheelRadius + rest - frontAnchorY;
    var rearAnchorVy = vy - angularVelocity * halfWheelBase;
    var frontAnchorVy = vy + angularVelocity * halfWheelBase;
    var rearForce = clamp(rearCompression * springK - rearAnchorVy * damper, 0, mass * gravity * 10);
    var frontForce = clamp(frontCompression * springK - frontAnchorVy * damper, 0, mass * gravity * 10);
    grounded = rearForce + frontForce > mass * gravity * 0.12;

    // Drive torque is strongest when the tyres are planted; airborne throttle spins the wheels but has less traction.
    var traction = grounded ? 1 : 0.22;
    if (gas) vx += (5.0 + engine * 0.72) * traction * dt;
    if (brake) {
        if (vx > 0.15) vx = Math.max(0, vx - (grounded ? 9.5 : 3.0) * dt);
        else vx -= (grounded ? 2.8 : 0.8) * dt;
    } else if (!gas) vx *= Math.max(0, 1 - 0.42 * dt);
    // Gravity also pulls the car down the hill; tyre grip and upgrades temper the slide.
    vx -= Math.sin(slope) * gravity * (grounded ? 0.36 : 0.08) * dt;
    var normalForce = rearForce + frontForce;
    vx += (-Math.sin(slope) * normalForce / mass) * dt;
    vy += (-gravity + Math.cos(slope) * normalForce / mass) * dt;
    if (grounded && !gas && !brake) vx *= Math.max(0, 1 - (0.18 + tyres * 0.025) * dt);
    vx = clamp(vx, -4.2, 9.2 + engine * 0.55);
    x += vx * dt;
    y += vy * dt;

    var targetAngle = Math.atan2(frontGround - rearGround, halfWheelBase * 2);
    var pitchFromSprings = (frontForce - rearForce) * 0.055;
    var align = grounded ? clamp(targetAngle - angle, -0.8, 0.8) * (2.6 + tyres * 0.12) - angularVelocity * (1.0 + suspension * 0.12)
        : -angularVelocity * 0.13;
    var driverTorque = grounded
        ? (gas ? 0.16 : 0) - (brake ? 0.22 : 0) + tilt * 0.55
        : tilt * 2.55 + (gas ? 0.62 : 0) - (brake ? 0.82 : 0);
    angularVelocity += (pitchFromSprings / inertia + align + driverTorque) * dt;
    angularVelocity = clamp(angularVelocity, -3.8, 3.8);
    angle += angularVelocity * dt;

    self.x = x; self.y = y; self.rotation = angle * 57.2957795;
    wheelSpin -= vx * dt / wheelRadius * 57.2957795;
    placeWheels(rearCompression, frontCompression, rest);
    if (exhaust) exhaust.setEmitting(gas);
    manager.send("setSpeed", vx);
    if (!grounded) manager.send("addAirTime", dt);

    // The driver's head is the fail point: landing upside down or burying the chassis ends the run.
    var headX = x + Math.cos(angle) * 0.10 - Math.sin(angle) * 0.56;
    var headY = y + Math.sin(angle) * 0.10 + Math.cos(angle) * 0.56;
    var localGround = groundAt(x);
    if (headY - 0.16 < groundAt(headX) + 0.02 || Math.abs(angle) > 1.58 || y < localGround - 1.4 || y < -20) {
        manager.send("endRun", "CRASH");
    }
}
"""

    private const val HAZARD_SCRIPT = """
var triggered = false;
function onTrigger(other) {
    if (triggered || other.tag != "Sensor") return;
    var manager = scene.find("Game Manager");
    if (!manager || !manager.send("isRunning")) return;
    triggered = true;
    manager.send("endRun", "WRECKED");
    self.destroy();
}
"""

    private const val PICKUP_SCRIPT = """
var collected = false;
var baseY = 0;
var phase = 0;
function start() { baseY = self.y; phase = random(0, 6.28); }
function update(dt) {
    if (self.tag == "Coin") {
        self.y = baseY + Math.sin(time.time * 3.8 + phase) * 0.12;
        self.rotation += 70 * dt;
    } else {
        self.y = baseY + Math.sin(time.time * 2.4 + phase) * 0.06;
    }
}
function onTrigger(other) {
    if (collected || other.tag != "Sensor") return;
    collected = true;
    var manager = scene.find("Game Manager");
    if (manager) manager.send("collect", self.tag);
    self.destroy();
}
"""
}
