# *SHIRO & PICO* — Episode 4: "Lost in the Alert Forest"

**Runtime**: Approx. 11 min (10 min main + 1 min Cyber Field Guide) / **Rating**: 7+ / TV-Y7-FV
**Today's Cyber Topic**: SIEM/SOAR correlation + alert fatigue countermeasures
**Today's Blunder**: Clicking every single alert and drowning in noise
**Today's Threat**: Zombie swarm (hijacked smart-city appliances and devices)

---

## 🎵 OP (10 seconds — same every episode)
Chibi-mode SHIRO and PICO spin and dance in a teal-lit city. The title logo **SHIRO & PICO** assembles itself from data particles.

---

## Scene 1 | Cold Open: Morning in Singapore Smart City

**[AERIAL SHOT]** A futuristic city rising from a sapphire strait. Countless delivery drones weave between skyscrapers. Every appliance in the city is connected — refrigerators, air conditioners, streetlights, even hospital IV pumps. All online. All the time.

**NARRATOR (SHIRO — quietly):** "When there is too much information, people lose sight of what matters. — That, too, is a kind of trap."

**[BUILDING MANAGEMENT CENTER]** A wall of monitors. Alert indicators begin to light up — *ping… ping… ping…* First a handful. Then — *BABABABABAM!* — an avalanche. The entire screen floods solid red.

**OPERATOR A:** "Wh-what IS this?! The alerts won't stop!"
**OPERATOR B:** "There are too many — I can't tell which ones are real…!"

**[BACK ALLEY — OUTSKIRTS OF CITY]** One by one, the city's appliances begin to droop, hazy blue *Zzz* bubbles drifting above them. A refrigerator murmurs *"Mmmm… ZZZ"* and nods off — the food inside begins to warm. A streetlight sighs *"Ugh… ZZZ"* and shuts its eyes — an intersection goes dark. In the **hospital wing** — an IV pump screen freezes with a *ZZZ*. A nurse spins around and shouts.

**NURSE:** "The pump stopped! Switch to manual — NOW!"

**[HAWKER STALL — ROADSIDE]** Chibi-mode SHIRO and PICO are slurping laksa.

**PICO** *(mid-spoonful):* "Oh MAN — Singapore laksa is the BEST thing ever!"
**SHIRO** *(sets down his bowl, eyes drifting to the distance):* "…Pico. Are you finished?"
**PICO:** "Huh? I've still got like—"

*Beep-beep-beep —* A device on SHIRO's wrist chimes. A holographic alert log unfolds in the air. SHIRO's eyes glow teal.

**SHIRO:** "…Every terminal in the city is being put to sleep. It's a **Zombie swarm**."
**PICO** *(sets the bowl down, stands):* "The hospital too?! …That's the kind of thing we can't leave alone."

His face tightens — just for a moment. Then snaps back to his usual grin.

**PICO:** "Alright, leave it to me! Time for a genius to step up~!"

---

## Scene 2 | The Blunder Cycle ①–③ (Swagger → Blunder → Panic)

**[BUILDING MANAGEMENT CENTER — SECURITY ROOM]** The two rush in. The entire wall blazes red. *Ping, ping, ping —* the alarm never stops. The counter reads **"4,231"→"4,298"→"4,361"** and keeps climbing.

**PICO** *(rolls up his sleeves, drops into the operator's chair with a thud):* "Easy, easy! If alerts are coming in, just deal with ALL of them, right?! My processing speed alone is genius-level, so—" ← **Swagger**

**SHIRO** *(steps back, folds his arms):* "…Hold on a sec—"

**PICO:** "No holding! Watch this! Technique — **FULL-SCAN BURST!!**"

PICO's hands fly across the console at superhuman speed. Click-confirm-clear, click-confirm-clear, click-confirm-clear— ← **Blunder begins**

Ten alerts down. Twenty. A grin spreads across PICO's face. *"Piece of cake."*

…But then.

For every alert he clears, **three new ones erupt in its place**. 4,400 — 4,600 — 5,000 —

**PICO:** "Huh? Wait — why is it going UP…?"

And worse: scrolling through the alerts PICO already "cleared" — the words **"HOSPITAL · IV PUMP · CRITICAL"** and **"POWER GRID · ANOMALY"** drift past and vanish. Critical alerts, swallowed by noise. ← **Blunder in full**

**PICO:** "Wait — wait wait wait — there's too many! They all look the SAME! Which ones are REAL?!" ← **Panic**

The screen goes completely, blindingly red. The alarm sound becomes a roaring flood — *BABABABABAM!*

**PICO** *(clutching his head, spinning):* "GAAAH! The more I look, the more there are! I'm DROWNING in alerts!!"

*Thump.*

SHIRO's hand settles quietly on PICO's shoulder.

---

## Scene 3 | SHIRO's Calm Assist + Transformation

**SHIRO** *(quiet, but certain):* "— Pico. Close your eyes."
**PICO:** "HUH?! NOW?!"
**SHIRO:** "Just one second. Stop trying to see everything at once. That's the very first move."

PICO squeezes his eyes shut, reluctantly. The alarm noise seems to hush — just for a breath. *(audio effect)*

**SHIRO** *(steps to the console, extends three fingers):* "Chasing every alert is — **the noise trap**. What matters is knowing which alert is **the real fire**. …Watch."

SHIRO's fingers move across the console — calm, deliberate, precise. Thousands of alerts lift off the screen as **particles of light**, swirling into the air. SHIRO begins sorting them — **slowly but surely** — red particles (critical), orange (warning), yellow (informational).

**SHIRO:** "**Collect the logs. Correlate them.** Look at the last hour's pattern — same source, same timing, same type of 'sleep signal' repeating. This is the **SIEM** approach. Not looking at everything — **looking at how things connect**."

The particles link together into **a single thread of light**. It points — to an old waterfront warehouse at the edge of the city.

**SHIRO:** "…The C2 — the command center — is there."
**PICO** *(opens his eyes, stares):* "…I was chasing every single alert… and I was just getting played by the noise…?"
**SHIRO:** "Yeah. **The Zombie swarm floods alerts on purpose — to blind us.** Old trick. But it works."
**PICO:** "…Ugh, I walked right into it. But—"

PICO's head snaps up. His eyes flash.

**PICO:** "— I'm getting to that warehouse FIRST!"

**SHIRO** *(a small smile):* "— Then let's do this for real."

**🎵 [TRANSFORMATION SEQUENCE]** *(same cut every episode)*
High-five — **"SHIRO-PICO, ON!"** — A teal cocoon of light wraps around them both. Their chibi proportions stretch fluidly into **5–6 head-height** battle forms. Light bursts —

**Signature pose**: SHIRO — three fingers raised, eyes narrowed. PICO — leaning forward, arms wide, smirking.

---

## Scene 4 | Action: SIEM Correlation + SOAR Auto-Response + C2 Severance

### ◆ Phase 1 — "The Alert Forest"

**[WATERFRONT WAREHOUSE — EXTERIOR]** Full-height forms, the two sprint in. Inside — **a swarm of Zombie-ified appliances**. Refrigerators, routers, security cameras, smart speakers — all drifting with eyes shut, *Zzz* bubbles floating above them, shambling in slow circles. And with every shuffle — *ping, ping —* fake alerts keep broadcasting.

**PICO:** "Whoa — so THIS is the alert factory… They're all sleepwalking and spamming noise!"
**SHIRO:** "Exactly. This is an **alert fatigue attack**. Flood the system with noise — hide the real threat inside it."

PICO starts to charge in.

**SHIRO:** "— Wait. **Don't hit them one by one.** They'll just multiply."
**PICO:** "I KNOW! I'll do it right this time! …But… how?"

**SHIRO:** "First — **put an automatic sorting system in place. — SOAR.**"

SHIRO draws a circle in the air with one finger. **A ring of light expands to wrap the entire warehouse.** The alert particles streaming off the Zombies get pulled into the ring — and **automatically sorted by color**. The red particles — critical — float up sharp and clear.

**SHIRO:** "**Automatic triage — prioritization.** Now only the real fires are visible."
**PICO:** "WHOA — the noise is GONE! Only the real ones are glowing!"

Three red particles remain. Hospital pump. Power grid. Water treatment facility. **The top three priorities.**

### ◆ Phase 2 — "Triple Wake Call"

**PICO:** "Okay — I'm hitting all three! Technique — **TRIPLE WAKE——!**"

PICO rockets in three directions simultaneously. Speed lines. Three afterimages streak through the air.

→ Hospital pump Zombie — *"Mmmm… huh?"* — awakens. Pump restarts.
→ Power grid Zombie — *"Mmmm… mornin'!"* — awakens. Streetlights flicker on.
→ Water treatment Zombie — *"Mmm~… oh!"* — awakens. Pump normalizes.

**PICO** *(catching his breath):* "Three — done——! That felt completely different from before! I didn't just swing at everything — I **woke up the highest-priority ones first!**"

**SHIRO** *(quiet nod):* "…Yeah. That's **triage**. Well done."

PICO looks almost embarrassed for a split second — then grins.

### ◆ Phase 3 — "Cut the Root"

**But —** Deep in the warehouse, suspended near the ceiling, an **old router** pulses red. The **C2 — command center**. As long as it keeps broadcasting its "sleep signal," the Zombies will keep multiplying.

**SHIRO:** "— **The C2 is still active.** Even if we wake every endpoint, they'll just be put back to sleep. **We have to cut the head.**"
**PICO:** "That red router… it's way up high!"

PICO kicks off the wall and leaps — Zombies shuffle and stumble into his path. PICO twists mid-air, weaving between them — **ducking and sidestepping** (never striking, never shoving) — climbing toward the ceiling.

**PICO:** "Excuse me, coming through — hey, you're all waking up soon, just hang tight!"

Near the ceiling. PICO reaches for the router — just out of reach. Ten centimeters short.

**PICO:** "Come ON — Shiro——!"

**[HELD FRAME — CHARGE]** SHIRO on the ground below. Eyes close. Three fingers raised in silence. Teal light gathers at his fingertips. Gathering. Gathering. Gathering —

**SHIRO:** "— **LOG FINALE.**"

**[RELEASE]** Three threads of light streak through the entire warehouse. **Every Zombie's alert broadcast cuts out simultaneously —** and in that pocket of silence, SHIRO's light drives straight into the C2 router.

**[RESONANCE]** The router's red pulse — *clink —* shifts to teal. The signal dies.

**[CHAIN REACTION]** A ripple rolls through the warehouse. One by one, the Zombies stir — *"Mmm~… oh!"* *"Mmmm… mornin'!"* *"Mm~… huh?"* — waking up all at once. Hazy blue *Zzz* bubbles dissolve. Eyes blink open, clear and bright.

**PICO** *(drops from the ceiling):* "They're ALL awake——!!"

**[SIGNATURE FREEZE FRAME + TECHNIQUE TITLE CARD]**
Against the sunset harbor, the two strike their poses.
SHIRO: three fingers lowered, eyes narrowed in profile.
PICO: fist in the air, face lit up.
Brushstroke title card: **"LOG FINALE"**

**[IMMEDIATE CUT]** Hospital monitor — normal readings. IV pump — running. The nurse exhales with relief. Streetlights — on. Inside the warehouse, the formerly Zombie appliances blink and murmur — *"Mornin'~"* *"Wait, where am I?"* *"I was so… sleepy…"* — coming to in a daze.

**PICO** *(hands on hips, surveying the room):* "…None of them even know they were being controlled."
**SHIRO:** "No. Because **Zombies are victims, too**. — They don't need to know. We just needed to wake them up. That's enough."

PICO goes quiet. Something crosses his face — like he's turning something over in his mind.

---

## Scene 5 | C-Part: Ghost Throughline + Seeds of PICO's Growth

**[HARBOR — DUSK WATERFRONT]** Back in chibi mode, the two sit side by side. The setting sun melts across the strait.

**PICO** *(swinging his feet):* "…Hey, Shiro. That SOAR thing — it automatically sorted the alerts, right? Who set it up?"
**SHIRO:** "Someone who thought carefully about the rules. **Which alerts count as critical. Which ones can be ignored.** — Humans decide that. Automation is a tool. The judgment belongs to people."
**PICO:** "…So what if the rules are wrong?"
**SHIRO** *(a beat):* "— Then you miss it. The real fire."

PICO looks at the water.

**PICO:** "…You know, Shiro. I used to be on the side that made the alerts. On purpose. Flood the system with noise, blind the admins — and sneak in while they couldn't see."
**SHIRO** *(expression unchanged, just listening):* "…Yeah."
**PICO:** "…Back then, hospitals, power grids — I never thought about any of that getting caught in the middle. It just felt like a game. Like a fun puzzle."

Silence. The sound of waves.

**SHIRO** *(quietly):* "…But you do think about it now."
**PICO:** "…………"

PICO starts to say something — and stops.

**[THEN —]** On the wall of the old harbor warehouse, a **faint white thermal trace** shimmers. SHIRO's eyes narrow to thin teal slits.

**SHIRO:** "…Pico. Look."
**PICO:** "Huh? That white mark — again?"
**SHIRO:** "It's a **Ghost trace**. Today's Zombie swarm — **this wasn't random**. Someone used the alert noise to *hide* something. …But what?"

SHIRO walks to the wall and reaches out, tracing it gently with one finger. Inside the trace — faint **fragments of data**. A string of numbers. SHIRO's eyes widen — just for a moment.

**SHIRO** *(low, almost to himself):* "…These coordinates. This location — it's—"

**PICO:** "Shiro? What is it?"

SHIRO smooths his expression. But something lingers behind his eyes.

**SHIRO:** "…Nothing. Let's go. Next city."
**PICO:** "Wait — hold on, you DEFINITELY just saw something!"
**SHIRO** *(flicks a transit ticket into the air):* "— We'll miss our connection."
**PICO:** "WAIT — hey!! How do you ALWAYS end the conversation with that——!!" *(breaks into a jog after him)*

**[FINAL SHOT]** The harbor wall. The white thermal trace fades like breath in wind. And for just a moment — **the silhouette of Ghost, faceless**, looking back at us. Watching. Watching. — Dissolving into the noise. Gone.

**On-screen text**: *To be continued —*

---

## 🔎 Cyber Field Guide (30 seconds — channel format)

Back in chibi mode, the two stand before a whiteboard. It reads **"THE ALERT FOREST"** — covered in a dense scattering of red dots.

**PICO:** "So today I clicked every single alert and completely drowned — why did that happen?"
**SHIRO:** "It's called **alert fatigue**. When there are so many alerts that the important ones become invisible. And flooding a system with noise to blind the defenders — that's not just a cartoon trick. It's a **real attack technique**."
**PICO:** "So what do you do about it?"
**SHIRO:** "Two tools. First — **SIEM**. Collect logs and **correlate** them. Connect scattered alerts and find the *pattern* underneath. Splunk and Microsoft Sentinel do this. And the second one—"
**PICO:** "The ring from earlier! The thing that sorted everything automatically!"
**SHIRO:** "**SOAR**. It automatically **triages** alerts — so only the critical ones reach a human. Palo Alto XSOAR, Splunk SOAR — tools like those. **SIEM gives you the big picture. SOAR takes automatic action. Use them together — that's the modern standard.**"
**PICO:** "So — me trying to look at everything was the mistake, and sorting first, *then* looking is the right call — GOT IT!" *(turns to camera, peace sign)*
**SHIRO:** "— **If you want to learn more, head over to 'Cyber Explained Channel' — check it out.**"

---

## 🎵 ED (10 seconds — same every episode)
Ghost peeks out from a hiding spot, notices us watching, and ducks back with a flustered little wave.

---

## Production Notes (Self-Review)

**Action grammar**: Technique names (FULL-SCAN BURST / TRIPLE WAKE / LOG FINALE) · charge-up hold (three fingers, light gathering, held frame) · release (three light threads streak) · resonance (ripple chain reaction) · signature freeze frame + brushstroke title card = *Demon Slayer / Naruto* style action grammar.

**Blunder cycle**: ① Swagger — "just click everything" → ② Blunder — alerts triple, critical warnings scroll past unread → ③ Panic — "I can't tell what's real" → SHIRO's calm assist — "close your eyes" — into the lesson beat.

**Authentic cybersecurity**: Alert fatigue (real attack vector) / SIEM correlation (connect logs to find patterns) / SOAR auto-triage (sort by severity) / C2 severance (cut the root, not the branches) / Zombie = victim (neutralize ≠ punish).

**7+ safe**: Zero blood, zero fear imagery, zero occult or religious iconography. Every Zombie wakes up with an adorable *"Mmm~… oh!"* "They're victims — we just wake them up, that's enough" makes the ethic of care explicit.

**Series throughline**: Ghost trace contains coordinate data → SHIRO reads it and goes quiet → thread continues.