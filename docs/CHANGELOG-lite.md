TraduzMods 1.0.0 Lite

Performance-first edition: translates only requested text and never advances the catalog in the background, uses a smaller native resource budget, and unloads the model after 20 seconds of inactivity.

- Translates missing mod text into Brazilian Portuguese exclusively with the local Tencent Hy-MT2-1.8B Q4_K_M model and keeps a reusable local disk cache. DeepL credentials and remote translation endpoints are no longer used.
- Extra RAM prompt caching is disabled (`--cache-ram 0`) to prevent the previous multi-gigabyte accumulation. The single-slot KV cache stays bounded by the context and is reused (`cache_prompt=true`).
- Both editions supervise native process memory, pause under memory pressure, and prevent simultaneous local model processes.
- Indexed terminology lookup and reused precompiled policy patterns reduce repeated work while preserving matching results.
- Escape literal ampersands when presenting FTB Quests titles/descriptions, preserving color codes, hexadecimal colors and backslashes without rewriting quest files or the disk cache.
- Remove the fixed resource-protection retry cooldown and countdown. The worker rechecks available resources at short intervals; RAM/VRAM admission checks and the resource watchdog remain active.
- Shared improvements for styled phrases, quest descriptions, structured NPC dialogue, and compatible FancyMenu plain text.
- A Minecraft-styled progress panel distinguishes saved translations from new translations in this session, shows free/minimum RAM and resource-wait reasons, and respects F1. The completion message lasts five seconds; controls remain accessible on screens with a free cursor.
- Native Minecraft pause/resume button in the progress panel, available on menus and screens with a free cursor. Pause keeps existing translations and pending work, stops new local inference, releases the owned local model asynchronously, and persists across restarts. Manual pause and memory protection have distinct labels; resume still respects resource protection.
- An independent saved-translation display switch keeps the disk cache and pending work intact. Turning display off does not pause inference or discard native pt-BR text; turning it back on reuses saved results without retranslating them. The preference persists across restarts.
- A persistent side tab minimizes/restores the panel without pausing translation.
- Subsequent visible queries can reuse the loaded local model; Lite keeps its 20-second idle limit, manual pause and memory safeguards.
- Open-screen text has priority and uses one-source foreground batches. Lite does not run catalog batches in the background.
- Existing translations and configuration are retained when switching editions.

Install exactly one edition for the correct Minecraft version and loader. Lite and Normal share the same mod ID and cannot be installed together. The local model and native runtime are downloaded on demand and require internet for that initial preparation; they are not bundled in this JAR. Inference runs locally once those assets are ready. Text embedded in image assets is not translated.
