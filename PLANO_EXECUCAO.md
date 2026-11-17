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
- [x] **0.4 Tirar o projeto do iCloud** — movido de `~/Documents/impulsotv.cloud` para **`~/Projetos/impulsotv`** (fora do iCloud). Git íntegro e build OK no novo local. No Android Studio: *File → Open* → `~/Projetos/impulsotv`.
- [x] **0.5 Repositório remoto** — `origin` = https://github.com/Jefilho-Git/app.impulsotv (privado); branch `main` publicada.

**Pronto quando:** `./gradlew :app:assembleDebug` funciona no terminal e o código está no git.

---

## Fase 1 — Bugs críticos já corrigidos (validar em campo)

Código alterado em 17/11/2026; compila, **falta teste real**.

- [x] **1.1 Realtime** — protocolo Phoenix v1, `payload.data`, reset só quando `restart_requested_at` muda. **Verificado no emulador (17/11):** `Subscribed to PostgreSQL`, eventos de `devices` sem reset falso, mudança de playlist aplicada em segundos.
- [x] **1.2 Recarga de playlist sem travar** — **verificado no emulador (17/11):** troca rápida, sem erros e sem tela preta.

**Roteiro de teste:**
- [ ] `adb logcat -s ImpulsoTV.Realtime ImpulsoTV.Player` mostra `Join enviado` seguido de `phx_reply: ok`.
- [ ] Adicionar/remover/reordenar item no dashboard → em segundos aparece `[PLAYLIST-UPDATE]` e a playlist muda ao fim do item atual, sem tela congelada.
- [ ] Trocar a playlist do dispositivo no dashboard → nova playlist entra.
- [ ] Pedir restart no dashboard → volta para pareamento **uma vez** (sem loop após parear de novo).
- [ ] Derrubar o Wi-Fi durante a reprodução → continua tocando; ao voltar, reconecta.

**Se o Realtime não conectar:** conferir no Supabase se `devices` e `playlist_items` estão na publicação `supabase_realtime` e se o papel `anon` tem SELECT nessas tabelas.

---

## Fase 2 — Prioridade alta (app)

- [~] **2.1 Recuperar conexão após início offline** — implementado (`f92934e`), falta teste no aparelho
  - Problema: iniciando pelo cache offline, heartbeat/Realtime/polling nunca iniciam; o dispositivo fica offline no dashboard para sempre.
  - Arquivos: `MainActivity.kt` (`onCreate`, `startPlayback`), `NetworkStateManager.kt`.
  - Solução: ao tocar do cache, observar a rede (`networkState`) e tentar `connectDevice` periodicamente; ao conseguir, iniciar heartbeat, Realtime e polling.
  - Teste: ligar a TV Box sem rede → toca do cache; ligar a rede → aparece online no dashboard em até 1 min.
  - Decisão: **conflict** no boot (ou 409 no heartbeat) → volta ao pareamento com mensagem, mantendo as mídias baixadas.

- [~] **2.2 Eliminar tela preta entre vídeos** — implementado (`9b212b8`), falta teste no aparelho
  - Problema: cada vídeo é carregado só quando o anterior termina; aparece fundo preto.
  - Arquivo: `MainActivity.kt` (`playVideo`, `initExoPlayer`).
  - Solução: `playerView.setKeepContentOnPlayerReset(true)` e enfileirar o próximo vídeo no ExoPlayer quando o próximo item também for vídeo.
  - Teste: playlist com 3 vídeos seguidos → nenhum frame preto visível.

- [~] **2.3 Corrigir Direct Boot** — implementado (`5d6c345`), falta teste no aparelho
  - Problema: `directBootAware` faz o app abrir antes do desbloqueio, quando SharedPreferences/Room/arquivos ainda não estão acessíveis → crash em aparelhos com criptografia por arquivo.
  - Arquivos: `AndroidManifest.xml`, `BootReceiver.kt`.
  - Solução: remover `directBootAware` e ignorar `LOCKED_BOOT_COMPLETED` (usar só `BOOT_COMPLETED`).
  - Teste: reiniciar a TV Box (`adb reboot`) 3 vezes → app abre e toca todas as vezes.

---

## Fase 3 — Segurança no Supabase (fora do app)

- [x] **3.0 Habilitar Realtime nas tabelas** — SQL `alter publication supabase_realtime add table public.devices, public.playlist_items;` executado em 17/11.
  `alter publication supabase_realtime add table public.devices, public.playlist_items;`
  (ou Database → Publications → `supabase_realtime` → ligar as duas tabelas). Até lá, mudanças chegam pelo polling de 5 min.
- [ ] **3.1 Auditar RLS atual** — listar as políticas reais de `devices`, `playlist_items`, `media_files` e o bucket `media-files`.
- [ ] **3.2 Fechar escrita anônima** — remover `UPDATE ... TO anon USING (true)` em `devices`; toda escrita passa pelas Edge Functions (`device-connect`, `device-heartbeat`, `device-disconnect`) com service role.
- [ ] **3.3 Restringir leitura anônima** — impedir listar todos os `devices`/códigos. Manter só o mínimo para o Realtime funcionar (avaliar filtrar por `connection_fingerprint` ou usar canal de broadcast emitido pelas functions).
- [ ] **3.4 Testar o app de ponta a ponta** com as novas políticas (pareamento, heartbeat, Realtime, reset).

> Atenção: 3.3 pode quebrar o Realtime de `postgres_changes`. Validar 1.1 antes e ter plano B (broadcast).

---

## Fase 4 — Prioridade média

- [x] **4.1 Permissão de sobreposição sem briga com o watchdog** — pedida 1x por instalação; watchdog suspenso até 2 min com a tela de configurações aberta. (`011e71a`)
- [x] **4.2 Alarme exato no Android 12+** — inexato quando não há permissão; erro sumiu do log no emulador. No Android 10 continua exato. (`011e71a`)
- [x] **4.3 Limite de cache** — fora da playlist: apaga após 72 h sem uso; mantém 500 MB livres (apaga os menos usados; sem espaço toca via rede). Valores em `AppConstants`. (`3a6603b`)
  - Valores adotados como padrão: 72 h e 500 MB livres — ajustar se necessário.
- [x] **4.4 Tempo de imagem a partir do carregamento** — timer no `onSuccess` + limite de segurança de 30s; verificado no emulador (10s exatos). (`289e185`, `fd3f072`)
- [~] **4.5 Heartbeat com dados reais** — versão via `BuildConfig` feita (`3a6603b`). `player_status` real **aguarda** confirmar quais valores a Edge Function `device-heartbeat` aceita.
- [x] **4.6 Pareamento robusto** — sem pedido duplicado, `device == null` tratado, conflito sem detalhes abre o diálogo, dispositivo sem playlist não trava. (`ca01422`)
- [x] **4.7 Rotação automática pelo painel** — Android fica no padrão (horizontal); mídia marcada como vertical no painel → o app gira o player 270° sozinho. Só gira com a tela deitada (sem rotação dupla); `display_mode` do painel tem prioridade; mídia que não combina com a tela aparece inteira com faixas pretas. Ângulo em `AppConstants.VERTICAL_ROTATION_DEGREES` (trocar para 90f se ficar de cabeça para baixo). Verificado no emulador em paisagem. (`aa902cc`)
  - Decisão (17/11): TVs verticais **não** serão mais configuradas no Android; cada TV recebe só um tipo de mídia.
- [ ] **4.8 Modo quiosque completo** — **revertido** (`8ab1918`): o filtro HOME fazia a TV perguntar qual launcher usar. Volta na fase de atualização automática (Device Owner), sem pergunta.
- [x] **4.9 Playlist vazia** — logo + aviso, fica online e começa assim que chegar mídia. (`ca01422`)

---

## Fase 5 — Manutenção e documentação

- [x] **5.1 Remover código morto** — −327 linhas: HeartbeatManager, funções PostgREST do SupabaseClient, detectMediaOrientation, wakelock da Activity, helpers sem uso. (`280d828`)
- [x] **5.2 Liberar callback do NetworkStateManager** — `release()` no onDestroy; rede voltando acelera a reconexão (2s). (`280d828`)
- [~] **5.3 Assinatura de release** — build lê `keystore.properties` (fora do git; modelo em `keystore.properties.example`); `assembleRelease` + R8 OK (9,3 MB) e abre sem erro. **Falta:** você gerar a keystore (comando na ARCHITECTURE.md §8) e guardá-la com as senhas fora do repositório. (`3e82226`)
- [ ] **5.4 Atualizar toolchain** — Kotlin 1.9 → 2.x e avisos do Gradle. Exige baixar novas dependências (Maven/Google) e testar de novo; o lint de release também precisa baixar `lint-gradle` (build online).
- [x] **5.5 Versionamento** — 3.1.0 (versionCode 4); o heartbeat envia `3.1.0-android`. (`5c5e7af`)
- [x] **5.6 Atualizar ARCHITECTURE.md** — reescrita para a 3.1.0 (Edge Functions, Realtime v1, rotação, cache, reconexão, release).

---

## Fase 6 — Atualização automática (Hostinger) + modo administrador

Decisões (17/11): chave de release **nova** (pacote definitivo `com.impulsolab.impulsotv`); instalar **ao ligar (15 min) e de madrugada (3h–5h)**; servidor `https://impulsotv.impulsolab.com.br/app/`. Nenhuma TV usa conta Google.

- [x] **6.1 Atualizador** — `versao.json` + APK na Hostinger; SHA-256, pacote e versão conferidos; reaproveita download. (`197ccd3`)
- [x] **6.2 Modo administrador** — instalação silenciosa, HOME = ImpulsoTV sem perguntar, hotspot bloqueado, Wi‑Fi religado, tela sempre ligada. Comandos adb `CHECK_UPDATE` / `RELEASE_ADMIN`. (`197ccd3`)
- [x] **6.3 Script de publicação** — `scripts/publicar.sh` gera `dist/impulsotv-X.Y.Z.apk` + `dist/versao.json`.
- [x] **6.4 Teste no emulador** — Device Owner ativo; atualização silenciosa 3.2.0 → 3.2.1 com retorno automático; APK adulterado recusado.
- [x] **6.5 Chave de release** — `~/Projetos/impulsotv-release.jks` (fora do repositório, `chmod 600`), alias `impulsotv`. Certificado `CN=ImpulsoLab, OU=ImpulsoTV, O=ImpulsoLab, L=Sao Paulo, ST=SP, C=BR`, **SHA-256 `9f2a80be5db16955cca8dcb08f1b19fb6670dcf4c2957f9693d062ca2e9bcf2a`**. Conferir um APK: `apksigner verify --print-certs <apk>`. ⚠️ Manter 2 cópias da chave + senha.
- [ ] **6.6 Primeira publicação** — `./scripts/publicar.sh` e subir `dist/` na Hostinger (APK primeiro, `versao.json` por último).
- [ ] **6.7 Migrar cada TV** (uma vez por TV — roteiro abaixo).
- [ ] **6.8 Validar na TV real** — reboot com hotspot bloqueado (Wi‑Fi deve manter), atualização silenciosa de madrugada.

### Roteiro de migração por TV (6.7)
```bash
T=<IP_DA_TV>:5555
adb connect $T
adb -s $T shell dumpsys account | grep "Accounts:"      # precisa ser 0 (remova contas em Configurações → Contas)
adb -s $T uninstall com.impulsolab.impulsotv.debug       # versão antiga (outra chave/pacote)
adb -s $T install dist/impulsotv-X.Y.Z.apk               # versão definitiva
adb -s $T shell dpm set-device-owner com.impulsolab.impulsotv/.admin.ImpulsoDeviceAdminReceiver
adb -s $T shell am start -n com.impulsolab.impulsotv/.ui.MainActivity
```
Depois: digitar o código de pareamento na TV (a desinstalação apaga a sessão) e conceder "Sobrepor a outros apps" se for pedido.

Comandos de manutenção:
```bash
P=com.impulsolab.impulsotv/com.impulsolab.impulsotv.admin.AdminCommandReceiver
adb -s $T shell am broadcast -a com.impulsolab.impulsotv.CHECK_UPDATE -n $P   # atualizar agora
adb -s $T shell am broadcast -a com.impulsolab.impulsotv.RELEASE_ADMIN -n $P  # tirar o modo administrador
```

---

## Decisões pendentes (resumo)

| ID | Pergunta | Resposta |
|---|---|---|
| 2.1 | Conflito no boot: tocar offline ou voltar ao pareamento? | Voltar ao pareamento (17/11/2026) |
| 4.3 | Teto de espaço e prazo para apagar mídia antiga? | Padrão: 72 h / 500 MB livres (ajustável) |
| 4.7 | Orientação física das TVs verticais? | Android padrão (horizontal); app gira 270° quando o painel marca vertical; 1 tipo de mídia por TV |
| — | O app será publicado na Play Store? (muda regras de FullScreenIntent / permissões) | |

---

## Diário

| Data | Item | O que foi feito | Status |
|---|---|---|---|
| 17/11/2026 | — | Análise completa do app e levantamento dos problemas | concluído |
| 17/11/2026 | 1.1, 1.2 | Realtime reescrito e recarga de playlist em segundo plano; build OK | aguardando teste em campo |
| 17/11/2026 | 0.1–0.3 | Limpeza, Gradle Wrapper 8.13, git iniciado com primeiro commit (já inclui 1.1/1.2) | concluído |
| 17/11/2026 | 0.4 | Projeto movido para `~/Projetos/impulsotv`; git e build validados | concluído |
| 17/11/2026 | 0.5 | Remoto `Jefilho-Git/app.impulsotv` configurado e `main` publicada — **Fase 0 concluída** | concluído |
| 17/11/2026 | 2.1 | Reconexão em segundo plano após início offline; conflito → pareamento | aguardando teste em campo |
| 17/11/2026 | 2.2 | Camadas vídeo/imagem + keepContentOnPlayerReset: sem tela preta | aguardando teste em campo |
| 17/11/2026 | 2.3 | Direct Boot removido (manifest + BootReceiver) | aguardando teste em campo |
| 17/11/2026 | — | Alvo definido: TV Box **Android 10+**; testes no emulador Android 13 (emulador A10 TV inviável no Mac ARM) | — |
| 17/11/2026 | 4.1–4.6, 4.8, 4.9 | Fase 4 (exceto 4.7) implementada e testada no emulador | concluído (4.5 parcial) |
| 17/11/2026 | 2.2 | Bug achado no teste: imagem não carregava com ImageView GONE; corrigido e verificado | concluído |
| 17/11/2026 | 2.1 | Reabrir o mesmo aparelho: `device-connect` 200 ok (sem falso conflito) | verificado no emulador |
| 17/11/2026 | 3.0 | Realtime recusa `postgres_changes` em `playlist_items`: publicação não habilitada no Supabase | pendente (Supabase) |
| 17/11/2026 | 3.0, 1.1, 1.2 | Realtime habilitado no Supabase; mudança de playlist chega em segundos, sem tela preta | concluído |
| 17/11/2026 | 4.7 | Conteúdo vertical deitado e cortado (rotação dupla). Agora: rotação automática 270° pelo painel, sem configurar o Android | concluído (validar na TV Box real) |
| 17/11/2026 | 5.1, 5.2, 5.5, 5.6 | Código morto removido, monitor de rede liberado, versão 3.1.0, ARCHITECTURE.md reescrita | concluído |
| 17/11/2026 | 5.3 | Assinatura de release configurada; keystore a ser gerada pelo usuário | aguardando keystore |
| 17/11/2026 | — | 3.1.0 testada no emulador: conexão 200, Realtime ok, loop e imagens ok | verificado |
| 17/11/2026 | — | **TV Box oficial** acessada via adb (172.16.50.215): Proeletronic PROSB3000, Android 10, 1280x720, rotação do sistema bloqueada, conta Google presente, app assinado com a chave de debug deste Mac | — |
| 17/11/2026 | 3.1.0 | Instalada na TV Box por cima da 3.0.0 (pareamento e cache mantidos); conexão, Realtime e loop OK | verificado na TV real |
| 17/11/2026 | Boot | Bug achado na TV: app não voltava à frente após atualizar/boot (processo morto antes do delay de 5s). Corrigido e verificado | concluído |
| 17/11/2026 | 4.8 | Revertido: pergunta de launcher na TV | adiado |
| 17/11/2026 | Boot | Reboot da TV Box: app abriu sozinho após o boot (correção do BootReceiver confirmada) | verificado na TV real |
| 17/11/2026 | Offline | Tela preta após reboot: servidor caiu logo após o device-connect e o app não usava a playlist salva. Corrigido (`c9d4f32`), testado no emulador (modo avião) e instalado na TV | concluído |
| 17/11/2026 | Wi‑Fi | Causa da queda do Wi‑Fi: o Ajustes da TV (`com.android.tv.settings` → `OpenWifiApService`) liga o **hotspot** a cada boot, o que derruba a conexão com a HSV-Wireless. Precisa desligar o hotspot na TV (ação do usuário) | pendente (usuário) |
| 17/11/2026 | 6.1–6.4 | Atualização automática + modo administrador implementados e testados no emulador (silenciosa, HOME sem pergunta, hotspot bloqueado, SHA-256) | concluído |
| 17/11/2026 | 6.5–6.8 | Falta: chave de release, primeira publicação na Hostinger e migração das TVs | pendente |
| 17/11/2026 | 6.5 | Chave de release criada; release 3.2.0 assinado e verificado (pacote definitivo `com.impulsolab.impulsotv`) | concluído |
