package com.sengine.engine.ai

/**
 * AI Prompt Templates for S Engine
 * Pre-built prompts for common game development tasks
 */
object AIPrompts {
    
    // ── Script Generation Templates ──
    
    fun playerController(movement: String = "walk and jump", abilities: String = "none"): String = """
Create a player controller script for S Engine.

Movement: $movement
Special Abilities: $abilities

Requirements:
- Smooth movement with acceleration/deceleration
- Input handling (keyboard, touch, gamepad)
- Collision detection
- Animation triggers (if applicable)
- Health/damage system
- Clean, well-commented code

Use S Engine API: self.x, self.y, self.vx, self.vy, input.axisX, input.aDown, etc.
""".trimIndent()
    
    fun enemyAI(behavior: String = "patrol and chase", difficulty: String = "medium"): String = """
Create an enemy AI script for S Engine.

Behavior: $behavior
Difficulty: $difficulty

Requirements:
- State machine (idle, patrol, chase, attack, retreat)
- Player detection (distance-based)
- Pathfinding or simple movement
- Attack patterns
- Health and damage
- Death handling

Use S Engine API with proper state management.
""".trimIndent()
    
    fun uiController(uiType: String = "main menu"): String = """
Create a UI controller script for S Engine.

UI Type: $uiType

Requirements:
- Button click handlers
- Text updates
- Panel show/hide
- Animations (fade, slide)
- Responsive layout
- Clean event handling

Use S Engine UI components: UICanvas, UIButton, UIText, etc.
""".trimIndent()
    
    // ── Level Design Templates ──
    
    fun platformerLevel(theme: String, difficulty: String, length: String): String = """
Design a $difficulty $length platformer level.

Theme: $theme

Include:
1. Level layout (platforms, obstacles, hazards)
2. Enemy placement and types
3. Collectibles (coins, power-ups)
4. Checkpoints
5. Level goal/exit
6. Environmental storytelling elements
7. Difficulty progression

Provide coordinates and specifications for each element.
""".trimIndent()
    
    fun rpgDungeon(theme: String, floors: Int, bossType: String): String = """
Design an RPG dungeon.

Theme: $theme
Number of Floors: $floors
Boss Type: $bossType

For each floor include:
1. Room layout and connections
2. Enemy types and placement
3. Treasure and loot
4. Puzzles or obstacles
5. Story elements/lore
6. Difficulty scaling

Provide detailed specifications for implementation.
""".trimIndent()
    
    // ── Dialogue Templates ──
    
    fun npcDialogue(npcName: String, npcPersonality: String, scenario: String): String = """
Write dialogue for an NPC.

Name: $npcName
Personality: $npcPersonality
Scenario: $scenario

Include:
1. Greeting
2. 3-5 conversation branches
3. Quest dialogue (if applicable)
4. Farewell
5. Reactive lines (if player returns)

Make it natural and character-appropriate.
""".trimIndent()
    
    fun questDialogue(questName: String, questGiver: String, questType: String): String = """
Write quest dialogue.

Quest Name: $questName
Quest Giver: $questGiver
Quest Type: $questType (fetch, kill, explore, escort, etc.)

Include:
1. Quest introduction
2. Objectives explanation
3. Hints/tips
4. Completion dialogue
5. Reward dialogue

Make it engaging and clear.
""".trimIndent()
    
    // ── Code Analysis Templates ──
    
    fun codeReview(code: String): String = """
Review this S Engine code:

```javascript
$code
```

Analyze:
1. Code quality and readability
2. Performance issues
3. Potential bugs
4. Memory leaks
5. Best practices violations
6. Suggestions for improvement

Provide specific, actionable feedback.
""".trimIndent()
    
    fun bugFix(code: String, symptoms: String): String = """
Fix this bug in S Engine code.

Code:
```javascript
$code
```

Symptoms: $symptoms

Provide:
1. Root cause analysis
2. Fixed code
3. Explanation of the fix
4. How to prevent similar bugs
""".trimIndent()
    
    fun performanceOptimization(code: String): String = """
Optimize this S Engine code for performance.

```javascript
$code
```

Analyze and optimize:
1. Update loop efficiency
2. Memory allocation
3. Object pooling opportunities
4. Collision detection optimization
5. Rendering optimizations
6. Script execution speed

Provide optimized code with explanations.
""".trimIndent()
    
    // ── Game Design Templates ──
    
    fun gameDesignDoc(genre: String, targetAudience: String, platform: String): String = """
Create a Game Design Document.

Genre: $genre
Target Audience: $targetAudience
Platform: $platform (Android)

Include:
1. Game Overview
   - Core concept
   - Unique selling points
   - Target audience analysis
   
2. Gameplay
   - Core mechanics
   - Controls
   - Game loop
   - Progression system
   
3. Content
   - Levels/worlds
   - Characters
   - Enemies/obstacles
   - Items/power-ups
   
4. Technical
   - Art style
   - Sound design
   - UI/UX design
   - Performance targets
   
5. Monetization (if applicable)
   - Business model
   - In-app purchases
   - Ads strategy
   
6. Development
   - Milestones
   - Timeline
   - Resource requirements

Be comprehensive and specific.
""".trimIndent()
    
    fun mechanicDesign(mechanicName: String, description: String): String = """
Design a game mechanic.

Name: $mechanicName
Description: $description

Provide:
1. Detailed explanation
2. Implementation approach
3. Player interaction
4. Balancing considerations
5. Edge cases
6. Testing scenarios
7. Sample code structure

Make it clear and implementable.
""".trimIndent()
    
    // ── Asset Generation Prompts ──
    
    fun spriteDescription(spriteName: String, style: String, purpose: String): String = """
Describe a game sprite.

Name: $spriteName
Art Style: $style
Purpose: $purpose

Provide detailed description for artist/AI generation:
1. Overall shape and silhouette
2. Color palette
3. Key details and features
4. Animation frames needed
5. Size specifications
6. Style references
""".trimIndent()
    
    fun soundEffectDescription(effectName: String, context: String): String = """
Describe a sound effect.

Name: $effectName
Context: $context

Provide detailed description for sound designer/AI generation:
1. Base sound characteristics
2. Duration
3. Pitch and tone
4. Effects (reverb, echo, etc.)
5. Looping requirements
6. Volume/dynamics
7. Reference sounds
""".trimIndent()
    
    // ── Quick Prompts ──
    
    val QUICK_PROMPTS = mapOf(
        "Platformer Movement" to playerController("walk, jump, double-jump", "dash, wall-jump"),
        "Enemy Patrol" to enemyAI("patrol back and forth, chase player when detected", "easy"),
        "Main Menu UI" to uiController("main menu with play, settings, quit buttons"),
        "Forest Level" to platformerLevel("enchanted forest", "medium", "medium-length"),
        "Dungeon Floor 1" to rpgDungeon("ancient ruins", 3, "stone golem"),
        "Wise Wizard NPC" to npcDialogue("Archmage Eldrin", "wise, mysterious, speaks in riddles", "player seeks guidance"),
        "Fetch Quest" to questDialogue("The Lost Artifact", "Village Elder", "fetch"),
        "Code Review" to "Review this code for quality and performance issues",
        "Game Design" to gameDesignDoc("2D Platformer", "casual gamers", "Mobile")
    )
}
