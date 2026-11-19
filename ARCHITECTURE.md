# ImpulsoTV — Documentação de Arquitetura

> **Versão:** 3.2.0 (versionCode 5) | **Atualizado:** 17/11/2026
> **Alvo:** TV Box **Android 10+** (API 29), Android no padrão horizontal.
> Player nativo (ExoPlayer + Coil) falando com o Supabase **só por Edge Functions** e Realtime.
> Roteiro de evolução e histórico: [PLANO_EXECUCAO.md](PLANO_EXECUCAO.md).

---

## 1. Visão geral

O app é um **player de sinalização digital em modo quiosque**. Cada TV Box é pareada a um
dispositivo do painel por um código de 6 caracteres e reproduz em loop a playlist desse
dispositivo. Mudanças feitas no painel chegam em segundos pelo Realtime.

```
┌────────────────────────────── TV Box ──────────────────────────────┐
│  MainActivity                                                       │
│   [PAIRING] código 6 chars   [SYNC] download   [PLAYER] loop        │
│        │                                         ExoPlayer (vídeo)  │
│        │                                         ImageView + Coil   │
│  SupabaseClient ─── Edge Functions        RealtimeClient ── WS      │
│  MediaCacheManager (filesDir/media)       OfflinePlaylistCache (Room)│
│  SessionManager (SharedPreferences)       NetworkStateManager       │
│  PlayerService (foreground 24/7, watchdog) ← BootReceiver (boot)    │
└─────────────────────────────────────────────────────────────────────┘
            │ HTTPS                                   │ WSS
   ┌────────▼─────────┐                    ┌──────────▼──────────┐
   │ Edge Functions   │                    │ Supabase Realtime   │
   │ device-connect   │                    │ postgres_changes:   │
   │ device-heartbeat │                    │  devices            │
   │ device-api       │                    │  playlist_items     │
   └──────────────────┘                    └─────────────────────┘
```

| Componente | Arquivo | Responsabilidade |
|---|---|---|
| `MainActivity` | `ui/` | Estados PAIRING / SYNC / PLAYER, loop, transições, rotação, reconexão |
| `SupabaseClient` | `data/remote/` | Edge Functions `device-connect`, `device-heartbeat`, `device-api` |
| `RealtimeClient` | `data/remote/` | WebSocket Phoenix (vsn 1.0.0) com reconexão automática |
| `MediaCacheManager` | `manager/` | Download e cache das mídias, limpeza e limite de espaço |
| `OfflinePlaylistCache` | `data/local/` | Última playlist em Room, para tocar sem internet |
| `SessionManager` | `manager/` | Sessão do pareamento e identificador (UUID) do aparelho |
| `NetworkStateManager` | `manager/` | Estado da rede; acelera a reconexão quando a rede volta |
| `PlayerService` | `service/` | Foreground service, wakelock parcial, traz o app de volta à frente |
| `BootReceiver` | `receiver/` | Abre serviço + app após `BOOT_COMPLETED` (não usa Direct Boot) |

---

## 2. Dados usados do Supabase

### `devices`
| Coluna | Uso no app |
|---|---|
| `id`, `device_code`, `name` | Sessão salva após o pareamento |
| `playlist_id` | Playlist atual; pode ser `null` (dispositivo ainda sem playlist) |
| `restart_requested_at` | Reset pelo painel — dispara só quando o **valor muda** |
| `is_connected` | `false` → app volta ao pareamento |

### `playlist_items` + `media_files` (entregues achatados pela `device-api`)
| Campo | Uso no app |
|---|---|
| `order_index` | Ordem de reprodução |
| `duration` | Segundos de exibição de imagens (padrão 30s); vídeos usam a duração própria |
| `display_mode` | **Escolha do painel**: `vertical`/`portrait` ou `horizontal`/`landscape` |
| `is_vertical`, `width`/`height` | Orientação do arquivo (fallback e decisão de preencher/encaixar) |
| `file_url`, `file_size`, `file_extension` | Download, nome no cache e validação de integridade |

---

## 3. Pareamento e retomada da sessão

```
Painel cria dispositivo → mostra código (ex.: UBPTXF) → pessoa digita na TV
        │
POST device-connect { device_code, fingerprint (UUID do app), connection_type: "android", force }
        ├─ "connected"/"ok"     → salva sessão + device_token + signal_topic → startPlayback()
        ├─ "conflict"           → diálogo "Dispositivo em uso" → [Sim] repete com force=true
        ├─ "pending_approval"   → o dono aprova em Dispositivos no painel; o app repete
        │                         com force=true a cada 5s, por até 15 min
        └─ outro / sem rede     → mensagem de erro na tela de pareamento
```

**Credenciais (Fase 2, servidor desde 18/11/2026).** Toda conexão bem-sucedida devolve um
`device_token` novo e um `signal_topic` novo; os anteriores deixam de valer. O app guarda os
dois em `SessionManager` e envia o token no header `x-device-token` para `device-api` e
`device-heartbeat`. Desconectar pelo painel apaga o token no banco. TVs sem token continuam
aceitas pelo modo antigo (código + fingerprint) até o servidor desligá-lo
(`DEVICE_LEGACY_AUTH=off`).

**Ao abrir o app com sessão salva** (`resumeSession`):

| Resposta do `device-connect` | Ação |
|---|---|
| `connected` / `ok` | Reprodução online |
| `disconnected` | Reset completo (sessão + mídias) → pareamento |
| `conflict` | Outro aparelho assumiu o código → pareamento **mantendo as mídias** |
| sem resposta | Toca a playlist offline (Room) e tenta reconectar em segundo plano: 10s → 60s, ou 2s depois que a rede volta. Sem playlist salva: "Aguardando conexão…" |

O heartbeat responder **409** ou `{"status":"disconnected"}` tem o mesmo tratamento de `conflict`.

---

## 4. Reprodução

```
startPlayback(): device-api → (vazia? tela "Nenhuma mídia" + checa a cada 30s)
              → SYNC: baixa todos os arquivos → PLAYER: playCurrentItem()
                                     │
           vídeo: ExoPlayer, avança em STATE_ENDED (erro → pula em 2s)
           imagem: Coil; o tempo conta a partir do onSuccess (+30s de limite de segurança)
                                     │
           advanceToNext(): playlist nova pronta? aplica e volta ao item 0
                            senão (índice + 1) % tamanho
```

### Transições sem tela preta
O `PlayerView` fica **sempre visível por baixo**; a imagem é a camada de cima.

| Transição | Como |
|---|---|
| Vídeo → vídeo | `keepContentOnPlayerReset`: o último quadro fica até o primeiro quadro do próximo |
| Imagem → vídeo | A imagem só some (fade 400ms) em `onRenderedFirstFrame` |
| Vídeo → imagem | Fade-in só depois de a imagem carregar |
| Imagem → imagem | A imagem anterior serve de placeholder até a nova carregar |

Um `itemToken` descarta callbacks atrasados de itens anteriores.

### Orientação (TVs em pé sem configurar o Android)
O Android da TV Box fica no **padrão (horizontal)**. Cada TV recebe só um tipo de mídia.

| Situação | Resultado |
|---|---|
| Mídia **marcada vertical** no painel + tela deitada | App gira o player **270°** (`AppConstants.VERTICAL_ROTATION_DEGREES`) |
| Tela já em pé (sistema girado / celular) | Não gira de novo |
| Orientação da mídia = da área do player | Preenche a tela (ZOOM / CENTER_CROP) |
| Orientações diferentes | Mídia inteira com faixas pretas (FIT) |

`display_mode` do painel tem prioridade sobre `is_vertical`; as dimensões do arquivo **nunca**
disparam rotação. Se a imagem ficar de cabeça para baixo na TV, troque o ângulo para `90f`.

---

## 5. Atualizações em tempo real

Canal secreto `realtime:{signal_topic}` (entregue pela `device-connect`), protocolo Phoenix
**v1**, autenticado pelo `apikey` da URL. Desde 18/11/2026 a chave pública **não lê** as
tabelas, então o app não usa mais `postgres_changes`: triggers no banco enviam **broadcasts**
nesse canal (`device_send_signal`).

| Broadcast | Ação |
|---|---|
| `device` com `revoked = true` e `is_connected = true` | Outro aparelho assumiu → pareamento mantendo as mídias |
| `device` com `is_connected = false` | Reset → pareamento |
| `device` com `restart_requested_at` diferente do último visto | **Reiniciar** (3.3.1+): procura versão nova na hora e instala; sem versão nova, reabre o app mantendo sessão e mídias |
| `device` com `playlist_id` diferente | Busca a playlist nova |
| `playlist` | Itens da playlist mudaram → busca a playlist nova |

Sessões sem `signal_topic` (salvas antes da 3.3.0) ainda tentam o canal antigo
`realtime:impulsotv-{device_id}`, que não recebe nada; a próxima conexão já entrega o tópico.

**Busca da playlist nova** (`requestPlaylistRefresh`): pela `device-api`, em segundo plano. Os
arquivos são baixados **antes** da troca, que acontece ao fim do item em exibição. Se a busca
falhar, a playlist atual continua tocando e o app tenta de novo em 8s. O **polling a cada 5 min**
usa o mesmo caminho, como reserva do Realtime.

O primeiro `restart_requested_at` visto após o pareamento vira só referência (sem reset).

**Requisito no Supabase:** migration `20261118120000_device_pairing_tokens.sql` (triggers de
sinal) e Realtime com canais públicos permitidos.

---

## 6. Cache de mídia

```
filesDir/media/MD5(url_sem_query).ext   (download em .tmp → rename)
```

- `getPlayableUri`: usa o arquivo local se o tamanho confere; senão usa a URL remota. Renova o `lastModified` (= último uso).
- `preCachePlaylist` (serializado com Mutex):
  1. Apaga arquivos **fora** da playlist sem uso há **72 h** e `.tmp` com mais de 1 h.
  2. Antes de cada download, garante **500 MB livres**, apagando primeiro os arquivos fora da playlist menos usados. Sem espaço suficiente, o item toca via rede.
- Arquivos da playlist atual nunca são apagados pela limpeza.
- Reset pelo painel → `clearAll()`. Conflito/takeover → mídias mantidas.

Valores em `AppConstants`: `CACHE_UNUSED_RETENTION_MS`, `CACHE_MIN_FREE_BYTES`.

---

## 7. Disponibilidade 24/7

- `PlayerService` em foreground (`mediaPlayback`) com wakelock parcial; a Activity mantém a tela ligada com `FLAG_KEEP_SCREEN_ON`.
- Watchdog: sem ping da Activity por 30s → traz o app para a frente. Fica suspenso por até 2 min enquanto uma tela do sistema (permissão de sobreposição) estiver aberta.
- Alarme de reforço: exato até o Android 11; inexato no 12+ sem `SCHEDULE_EXACT_ALARM`.
- Permissão "Sobrepor a outros apps": pedida **uma vez por instalação** (marca em `impulsotv_device`, que o reset não apaga).
- `BootReceiver`: `BOOT_COMPLETED`, `QUICKBOOT_POWERON`, `MY_PACKAGE_REPLACED` → serviço + app após 5s.
- Pode ser o launcher (`HOME`) da TV Box — ver §8.

---

## 8. Build, instalação e operação

### Configuração
`SUPABASE_URL` e `SUPABASE_ANON_KEY` (chave publishable) ficam em `app/build.gradle` (`buildConfigField`).

### Build
```bash
./gradlew :app:assembleDebug     # app-debug.apk, pacote com.impulsolab.impulsotv.debug
./gradlew :app:assembleRelease   # app-release.apk (R8), pacote com.impulsolab.impulsotv
```

### Assinatura de release
1. Gere a chave **uma vez** e guarde-a fora do repositório:
   ```bash
   keytool -genkeypair -v -keystore ../impulsotv-release.jks -alias impulsotv \
     -keyalg RSA -keysize 2048 -validity 10000
   ```
2. Copie `keystore.properties.example` para `keystore.properties` e preencha (o arquivo está no `.gitignore`).

Sem `keystore.properties`, o release é assinado com a chave de debug, com um aviso no build.
**Sem a mesma chave, não é possível atualizar o app já instalado nas TVs.**

### Instalação e logs
```bash
adb connect <IP_DA_TV_BOX>:5555
adb install -r app/build/outputs/apk/release/app-release.apk
adb logcat -s ImpulsoTV.Main ImpulsoTV.Player ImpulsoTV.Supabase \
              ImpulsoTV.Realtime ImpulsoTV.Cache ImpulsoTV.Service ImpulsoTV.Network
```

### Quiosque (opcional)
```bash
adb shell cmd package set-home-activity com.impulsolab.impulsotv/.ui.MainActivity
```

---

## 9. Navegação no código por tags (logs e comentários)

| Tag | Onde | O que é |
|---|---|---|
| `[STATE]` | MainActivity | Transições PAIRING / SYNC / PLAYER |
| `[PAIRING-UI]` | MainActivity | Tela e fluxo de pareamento |
| `[RECONNECT]` | MainActivity | Retomada da sessão e reconexão após início offline |
| `[SIGNAL]` | MainActivity / RealtimeClient | Reset, desconexão e takeover |
| `[PLAYLIST-LOOP]` | MainActivity | Ciclo de reprodução |
| `[PLAYLIST-UPDATE]` | MainActivity / RealtimeClient | Busca e troca de playlist em segundo plano |
| `[EMPTY]` | MainActivity | Playlist vazia |
| `[ITEM-VIDEO]` / `[ITEM-IMAGE]` | MainActivity | Reprodução de vídeo / imagem |
| `[TRANSITION]` | MainActivity | Camadas e fades |
| `[ORIENTATION]` | MainActivity | Rotação automática e preencher/encaixar |
| `[REALTIME-PROTOCOL]` / `[RESTART]` | RealtimeClient | Protocolo Phoenix e regra do reset |
| `[FUNCTION]` | SupabaseClient | Chamadas às Edge Functions |
| `[CLEANUP]` / `[DISK-FULL]` | MediaCacheManager | Limpeza e limite de espaço |
| `[ACTIVITY-MONITOR]` / `[SCREEN-EVENT]` | PlayerService | Watchdog e retorno à frente |

---

## 11. Atualização automática e modo administrador (Fase 6)

**Publicar uma versão:** aumente `versionCode`/`versionName` em `app/build.gradle`, rode
`./scripts/publicar.sh` e suba `dist/` para `https://impulsotv.impulsolab.com.br/upload/app/`
(APK primeiro, `versao.json` por último).

```json
{ "versionCode": 5, "versionName": "3.2.0", "url": "impulsotv-3.2.0.apk", "sha256": "…" }
```

**Na TV** (`update/UpdateManager.kt`): confere a cada hora (1ª vez 2 min após iniciar) e instala
só até **15 min após ligar** ou das **3h às 5h**. Antes de instalar, confere SHA-256, pacote e versão;
o Android ainda exige a **mesma chave de release**. Após instalar, o `BootReceiver`
(`MY_PACKAGE_REPLACED`) reabre o app. Em builds de debug a verificação automática fica desligada.

**Modo administrador** (`admin/DeviceOwner.kt`), ativado uma vez por TV com
`dpm set-device-owner` (sem contas no aparelho):

| Política | Efeito |
|---|---|
| Instalação silenciosa | Atualiza sem ninguém apertar "Instalar" |
| HOME = `HomeActivity` | O HOME abre o player (ponte invisível, sem segunda cópia) e não pergunta qual launcher |
| `DISALLOW_CONFIG_TETHERING` | O Ajustes da TV não consegue ligar o hotspot no boot (que derrubava o Wi‑Fi) |
| Wi‑Fi religado | Se desligado e sem cabo, o app religa (permitido só a Device Owner no Android 10+) |
| `STAY_ON_WHILE_PLUGGED_IN=7` | Tela sempre ligada na tomada |

Sem o modo administrador, a atualização abre a confirmação do Android e o resto é ignorado.
Roteiro de migração das TVs e comandos de manutenção: [PLANO_EXECUCAO.md](PLANO_EXECUCAO.md), Fase 6.

---

## 10. Pendências conhecidas

- **Segurança do Supabase:** desde 18/11 a chave pública **não lê nem escreve** nas tabelas (Fase 1 do repositório web). O app fala só com as Edge Functions e com o canal de sinal.
- **Relógio das TVs:** `last_checkin` usa o relógio da TV; algumas aparecem horas atrasadas.
- **Rede das TVs:** o adb da PROSB3000 aceita conexões sem autenticação na porta 5555. Pedido à TI em `docs/pedido-ti-rede-tvs.md`.
- `player_status` do heartbeat é sempre `"playing"` (4.5), até confirmar os valores aceitos pela `device-heartbeat`.
- Religamento do Wi‑Fi (`WifiGuard`) instalado, mas ainda não observado numa queda real causada pelo hotspot.

## Coleta de mídia (3.4.0)

`manager/MediaTelemetry.kt` registra cada download do `MediaCacheManager`
(Content-Length declarado e status HTTP; nunca URL, nome de arquivo ou código)
numa fila em memória de até 200 eventos. O heartbeat envia lotes de até 50 para
a Edge Function `media-telemetry` com `x-device-token`, a cada 30 s.

- Confirmado (2xx) ou recusado (4xx): sai da fila.
- 503 (coleta desligada no servidor): pausa os envios por 1 hora.
- Sem resposta, 429 ou 5xx: tenta de novo com o mesmo `request_id` (sem duplicar).
- Sem token (modo antigo): não envia.
- Mídia tocada direto da rede (disco cheio) e cache local não são registrados.

Para desligar sem novo APK: `MEDIA_TELEMETRY_V2_ENABLED=false` nos segredos do Supabase.

## Ficha técnica (3.5.0)

`manager/PlayerStatus.kt` monta, a cada heartbeat, o campo `player_status`
enviado para `device-heartbeat`:

- `now_playing`: ID da mídia (o painel resolve o nome), vídeo ou imagem,
  posição na playlist, desde quando e se toca do armazenamento (`cache`) ou da
  internet (`network`). Atualizado em `playVideo`/`showImage`; limpo quando a
  playlist esvazia ou a TV desconecta.
- `device`: fabricante, modelo, Android, SDK e resolução da tela.
- `storage`: espaço livre e total de `filesDir`, tamanho e quantidade de mídias guardadas.
- `network` (wifi, ethernet, cellular, other, none) e `uptime_ms` (tempo ligado).

Qualquer erro ao montar a ficha só a omite: o heartbeat e o player seguem.
O servidor filtra os campos (`supabase/functions/_shared/player-status.ts` no
repositório do painel) e grava em `devices.player_status`.

## Andamento da atualização (3.5.1)

`update/UpdateStatus.kt` guarda em memória a etapa da atualização em curso e a
ficha técnica envia em `player_status.update`: `downloading` (com `progress` de
5 em 5%), `verifying`, `installing`, `waiting_confirmation` (tela "Instalar?" do
Android aberta) ou `failed` com `error` (`no_space`, `download_http`,
`download_failed`, `checksum`, `invalid_apk`, `install_failed`, `error`).
Sem atualização em andamento o campo não vai. Nada aqui interrompe a instalação.
