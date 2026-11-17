# ImpulsoTV — Plano de Execução

> Documento vivo. Atualize o status de cada item ao concluir e registre no **Diário** no final.
> Regra: **uma fase por vez**, cada item termina com build verde + teste no aparelho.

**Legenda:** `[ ]` pendente · `[~]` em andamento · `[x]` concluído · `[!]` bloqueado / precisa decisão

---

## Como trabalhar cada item

1. Ler o item e os arquivos citados.
2. Implementar a correção.
3. Compilar: `./gradlew :app:assembleDebug` (após o item 0.2; antes disso, pelo Android Studio).
4. Instalar e testar na TV Box com o roteiro do item (`adb install -r app/build/outputs/apk/debug/app-debug.apk`).
5. Commitar com mensagem que cite o ID do item (ex.: `fix(2.1): retoma rede após início offline`).
6. Marcar `[x]` aqui e anotar no Diário.

---

## Fase 0 — Fundação (fazer antes de tudo)

Sem isso, qualquer mudança errada é difícil de desfazer.

- [x] **0.1 Versionar com git** — `git init` (branch `main`), `.gitignore` (build/, .gradle/, .idea/, local.properties, *.apk, chaves, .DS_Store), primeiro commit com o estado atual.
- [x] **0.2 Adicionar Gradle Wrapper** — `gradlew`/`gradlew.bat` (Gradle 8.13); `./gradlew :app:assembleDebug` compila do zero.
- [x] **0.3 Limpar lixo** — removidos `{app`, `build/`, `app/build/` (com os duplicados do iCloud) e `.DS_Store`.
- [!] **0.4 Tirar o projeto do iCloud** — confirmado: `~/Documents` está sincronizada com o iCloud. Mover para fora (ex.: `~/Projetos/impulsotv`) com o Android Studio fechado e reabrir o projeto no novo local. Evita novos duplicados e corrupção do `.git`.
- [!] **0.5 Repositório remoto** — criar repositório privado (GitHub) e fazer push. Precisa da conta/organização onde o repositório vai ficar.

**Pronto quando:** `./gradlew :app:assembleDebug` funciona no terminal e o código está no git.

---

## Fase 1 — Bugs críticos já corrigidos (validar em campo)

Código alterado em 17/11/2026; compila, **falta teste real**.

- [~] **1.1 Realtime** — protocolo Phoenix v1 em JSON objeto, leitura de `payload.data`, sem `access_token`, reset só quando `restart_requested_at` muda, troca de playlist detectada. (`RealtimeClient.kt`)
- [~] **1.2 Recarga de playlist sem travar** — busca via `device-api` em segundo plano, aplica ao fim do item, retry em falha, pré-cache serializado. (`MainActivity.kt`, `MediaCacheManager.kt`)

**Roteiro de teste:**
- [ ] `adb logcat -s ImpulsoTV.Realtime ImpulsoTV.Player` mostra `Join enviado` seguido de `phx_reply: ok`.
- [ ] Adicionar/remover/reordenar item no dashboard → em segundos aparece `[PLAYLIST-UPDATE]` e a playlist muda ao fim do item atual, sem tela congelada.
- [ ] Trocar a playlist do dispositivo no dashboard → nova playlist entra.
- [ ] Pedir restart no dashboard → volta para pareamento **uma vez** (sem loop após parear de novo).
- [ ] Derrubar o Wi-Fi durante a reprodução → continua tocando; ao voltar, reconecta.

**Se o Realtime não conectar:** conferir no Supabase se `devices` e `playlist_items` estão na publicação `supabase_realtime` e se o papel `anon` tem SELECT nessas tabelas.

---

## Fase 2 — Prioridade alta (app)

- [ ] **2.1 Recuperar conexão após início offline**
  - Problema: iniciando pelo cache offline, heartbeat/Realtime/polling nunca iniciam; o dispositivo fica offline no dashboard para sempre.
  - Arquivos: `MainActivity.kt` (`onCreate`, `startPlayback`), `NetworkStateManager.kt`.
  - Solução: ao tocar do cache, observar a rede (`networkState`) e tentar `connectDevice` periodicamente; ao conseguir, iniciar heartbeat, Realtime e polling.
  - Teste: ligar a TV Box sem rede → toca do cache; ligar a rede → aparece online no dashboard em até 1 min.
  - [!] Decisão: no boot, se o servidor responder **conflict** (outro aparelho assumiu o código), o app deve tocar offline ou voltar ao pareamento?

- [ ] **2.2 Eliminar tela preta entre vídeos**
  - Problema: cada vídeo é carregado só quando o anterior termina; aparece fundo preto.
  - Arquivo: `MainActivity.kt` (`playVideo`, `initExoPlayer`).
  - Solução: `playerView.setKeepContentOnPlayerReset(true)` e enfileirar o próximo vídeo no ExoPlayer quando o próximo item também for vídeo.
  - Teste: playlist com 3 vídeos seguidos → nenhum frame preto visível.

- [ ] **2.3 Corrigir Direct Boot**
  - Problema: `directBootAware` faz o app abrir antes do desbloqueio, quando SharedPreferences/Room/arquivos ainda não estão acessíveis → crash em aparelhos com criptografia por arquivo.
  - Arquivos: `AndroidManifest.xml`, `BootReceiver.kt`.
  - Solução: remover `directBootAware` e ignorar `LOCKED_BOOT_COMPLETED` (usar só `BOOT_COMPLETED`).
  - Teste: reiniciar a TV Box (`adb reboot`) 3 vezes → app abre e toca todas as vezes.

---

## Fase 3 — Segurança no Supabase (fora do app)

- [ ] **3.1 Auditar RLS atual** — listar as políticas reais de `devices`, `playlist_items`, `media_files` e o bucket `media-files`.
- [ ] **3.2 Fechar escrita anônima** — remover `UPDATE ... TO anon USING (true)` em `devices`; toda escrita passa pelas Edge Functions (`device-connect`, `device-heartbeat`, `device-disconnect`) com service role.
- [ ] **3.3 Restringir leitura anônima** — impedir listar todos os `devices`/códigos. Manter só o mínimo para o Realtime funcionar (avaliar filtrar por `connection_fingerprint` ou usar canal de broadcast emitido pelas functions).
- [ ] **3.4 Testar o app de ponta a ponta** com as novas políticas (pareamento, heartbeat, Realtime, reset).

> Atenção: 3.3 pode quebrar o Realtime de `postgres_changes`. Validar 1.1 antes e ter plano B (broadcast).

---

## Fase 4 — Prioridade média

- [ ] **4.1 Permissão de sobreposição sem briga com o watchdog** — pausar o monitor do `PlayerService` enquanto a tela de permissão estiver aberta; pedir a permissão só uma vez (lembrar se já pediu). (`MainActivity.kt`, `PlayerService.kt`)
- [ ] **4.2 Alarme exato no Android 12+** — trocar `setExactAndAllowWhileIdle` por `set` (não precisa ser exato) ou declarar `SCHEDULE_EXACT_ALARM`; atualizar `lastPingTime` mesmo se falhar. (`PlayerService.kt`)
- [ ] **4.3 Limite de cache** — apagar arquivos fora da playlist atual após 24–72 h e impor teto de espaço (ex.: 70% do livre). (`MediaCacheManager.kt`)
  - [!] Decisão: qual o teto de espaço e o prazo para apagar mídia antiga?
- [ ] **4.4 Tempo de imagem a partir do carregamento** — iniciar o timer no `onSuccess` do Coil. (`MainActivity.kt`, `showImage`)
- [ ] **4.5 Heartbeat com dados reais** — `player_status` refletindo estado (syncing/playing/empty/error) e versão via `BuildConfig.VERSION_NAME`. (`MainActivity.kt`, `SupabaseClient.kt`)
- [ ] **4.6 Pareamento robusto** — tratar `device == null`, conflito sem `conflictInfo`, e evitar pedido duplicado (6 letras + Enter). (`MainActivity.kt`)
- [ ] **4.7 Rotação única** — escolher **uma** estratégia (girar a View **ou** pedir orientação ao sistema), não as duas. (`MainActivity.kt`, `applyOrientation`)
  - [!] Decisão: as TVs verticais são montadas giradas para qual lado? Testar em pelo menos 2 modelos de TV Box.
- [ ] **4.8 Modo quiosque completo** — adicionar intent-filter `HOME` + `DEFAULT` à `MainActivity` para poder virar launcher padrão. (`AndroidManifest.xml`)
- [ ] **4.9 Playlist vazia** — mostrar mensagem/logo em vez de tela preta enquanto não há itens.

---

## Fase 5 — Manutenção e documentação

- [ ] **5.1 Remover código morto** — `HeartbeatManager`, `findDeviceByCode`, `registerDeviceConnection`, `sendHeartbeat`, `setDeviceOffline`, `disconnectDevice`, `fetchPlaylistItems`, `detectMediaOrientation`, wakelock da Activity.
- [ ] **5.2 Liberar callback do NetworkStateManager** no `onDestroy`.
- [ ] **5.3 Assinatura de release** — criar keystore própria (fora do repositório) e `signingConfigs.release`; testar build `assembleRelease` com R8.
- [ ] **5.4 Atualizar toolchain** — Kotlin 1.9 → 2.x, resolver avisos de depreciação do Gradle.
- [ ] **5.5 Versionamento** — incrementar `versionCode`/`versionName` a cada entrega.
- [ ] **5.6 Atualizar ARCHITECTURE.md** — display mode, fingerprint UUID, Edge Functions, Realtime v1, fluxo de atualização em segundo plano.

---

## Decisões pendentes (resumo)

| ID | Pergunta | Resposta |
|---|---|---|
| 2.1 | Conflito no boot: tocar offline ou voltar ao pareamento? | |
| 4.3 | Teto de espaço e prazo para apagar mídia antiga? | |
| 4.7 | Orientação física das TVs verticais? | |
| — | O app será publicado na Play Store? (muda regras de FullScreenIntent / permissões) | |

---

## Diário

| Data | Item | O que foi feito | Status |
|---|---|---|---|
| 17/11/2026 | — | Análise completa do app e levantamento dos problemas | concluído |
| 17/11/2026 | 1.1, 1.2 | Realtime reescrito e recarga de playlist em segundo plano; build OK | aguardando teste em campo |
| 17/11/2026 | 0.1–0.3 | Limpeza, Gradle Wrapper 8.13, git iniciado com primeiro commit (já inclui 1.1/1.2) | concluído |
| 17/11/2026 | 0.4, 0.5 | Projeto está no iCloud; mudança de pasta e repositório remoto aguardam decisão | pendente |
