# Worker — Fix cache token parsing + pricing (agentic-helper 1.29.0)

## Mission

agentic-helper actuel parse uniquement `input_tokens` et `output_tokens` des
réponses Anthropic/OpenAI. Or :

- **Anthropic API**: `input_tokens` ne contient QUE les tokens uncached. Les
  champs `cache_creation_input_tokens` et `cache_read_input_tokens` sont
  séparés et nous les perdons.
- **OpenAI API**: `prompt_tokens` contient TOUT (cached + uncached). Les
  cached sont dans `prompt_tokens_details.cached_tokens`. La lib facture tout
  au prix input plein, ratant la réduction -90% sur cached.

→ On sous-facture quand cache hit côté Anthropic (cache_read perdu) et on
sur-facture côté OpenAI (cached non discounted).

Le cache_control est DÉJÀ activé par défaut sur ClaudeAdapter (Anthropic +
Azure-Anthropic). Donc le cache fonctionne probablement, on perd juste l'info
côté parsing.

## Tu DOIS faire

1. **`ClaudeResponse$Usage`** — ajouter 2 champs :
   ```java
   @JsonProperty("cache_creation_input_tokens")
   private Integer cacheCreationInputTokens;
   @JsonProperty("cache_read_input_tokens")
   private Integer cacheReadInputTokens;
   ```

2. **`TokenUsage`** — ajouter 2 champs :
   ```java
   private Integer cacheCreationTokens;
   private Integer cacheReadTokens;
   ```
   Update `accumulate()` pour les sommer. Update `@Data` + `@Builder` (Lombok
   les gère).

3. **`ModelPricing`** — étendre le calcul :
   - Changer `double[]` de 2 à 4 entrées : `{input, output, cacheCreate, cacheRead}`.
   - Modifier `put()` pour accepter 4 paramètres avec défauts à 0 si non spécifié.
   - Garder l'API publique existante `calculate(model, in, out)` (cache=null) pour rétrocompat.
   - Ajouter nouvelle signature `calculate(model, in, out, cacheCreate, cacheRead)`.
   - Idem pour la version avec fallback.
   - Pricing par modèle :
     - Anthropic Sonnet 4.x / Opus 4.x / Haiku 4.x : cacheCreate = 1.25 × input, cacheRead = 0.10 × input. Source : https://www.anthropic.com/pricing#api
     - OpenAI GPT-5.x / GPT-4.x / o-series : cacheCreate = 0 (pas facturé séparément), cacheRead = 0.10 × input. Source : https://openai.com/api/pricing/
     - Mistral / DeepSeek / Gemini / Grok : cacheCreate = 0, cacheRead = 0 (ces providers ne supportent pas le cache dans ta lib pour l'instant).

4. **`UnifiedRequestService`** — call sites Claude/OpenAI :
   - Anthropic site (~line 1180) :
     ```java
     tokenUsage = calculatePricing(
         response.getModel(),
         response.getUsage().getInputTokens(),
         response.getUsage().getOutputTokens(),
         response.getUsage().getCacheCreationInputTokens(),
         response.getUsage().getCacheReadInputTokens(),
         instance);
     ```
   - OpenAI chat completion site (~line 2652) :
     ```java
     Integer cached = null;
     if (chatResponse.getUsage().getPromptTokensDetails() != null)
         cached = chatResponse.getUsage().getPromptTokensDetails().getCachedTokens();
     int uncached = chatResponse.getUsage().getPromptTokens() != null
         ? chatResponse.getUsage().getPromptTokens() - (cached != null ? cached : 0)
         : 0;
     TokenUsage tokenUsage = calculatePricing(model, uncached, completionTokens, null, cached, instance);
     ```
   - Faire pareil sur tous les autres sites qui appellent `calculatePricing` (grep "calculatePricing" dans le fichier).
   - Ajouter overload `calculatePricing(model, in, out, cacheCreate, cacheRead, instance)`.

5. **Logging** — `ModelPricing.formatForLog(TokenUsage)` :
   - Si cacheCreate ou cacheRead non-nuls, les afficher : 
     `Tokens: 50->10 (cc=200 cr=500) | Cost: $0.001234`.

6. **Tests** (`ModelPricingTest.java`) :
   - Ajouter cas avec cache pour `claude-sonnet-4-5` (in=100, out=50, cc=1000, cr=500) → cost = 100*3/M + 50*15/M + 1000*3.75/M + 500*0.30/M = ... verify exact.
   - Cas pour `gpt-5.4` (in=100, out=50, cc=0, cr=500) → cost = 100*2.5/M + 50*15/M + 0 + 500*0.25/M.
   - Cas où cache=null doit donner identique à l'ancienne API (rétrocompat).
   - Cas Mistral (in=100, out=50, cc=200, cr=100) → cache ignored (rates=0), cost = juste input+output.

7. **README** — ajouter une section "Prompt caching" qui explique :
   - Le `cache_control` est activé automatiquement sur le system prompt pour Claude/Azure-Claude.
   - `TokenUsage` expose `cacheCreationTokens` et `cacheReadTokens`.
   - `ModelPricing.calculate` les prend en compte si fournis.

8. **Version bump** : pom.xml de 1.28.0 → 1.29.0.

9. **Build + tests** : `mvn -q test` doit passer.

10. **Commit + tag + push** :
    - 1 commit unique : `feat(usage): parse and price cache tokens for Anthropic + OpenAI (1.29.0)`.
    - Tag git : `v1.29.0`.
    - Push origin + tag.

## Contraintes

- **Rétrocompat** : l'ancienne API `ModelPricing.calculate(model, in, out)` reste publique et retourne un TokenUsage avec `cacheCreationTokens=null, cacheReadTokens=null, estimatedCostUsd` calculé sans cache.
- **Pas de feature flag** : le parsing des nouveaux fields est toujours actif (même quand `null`/`0` retourné par l'API, c'est sans impact).
- **Pas d'amendement de commit**, commit atomique unique.
- **Mode tested** : `mvn test` doit passer avant commit.

## Output

- Commit poussé sur `origin/main` (branche `main`).
- Tag `v1.29.0` poussé.
- Rapport bref (< 200 mots) : SHA du commit, n_tests avant/après, fichiers touchés.
