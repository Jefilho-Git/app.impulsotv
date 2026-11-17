# ImpulsoTV v3 — Documentação de Arquitetura

> **Versão:** 3.0.0 | **Data:** Maio 2026
> **Mudança principal:** Eliminação completa do WebView. Player nativo com ExoPlayer + Supabase direto.

---

## 1. Por que eliminar o WebView?

| Problema (WebView) | Solução (Nativo) |
|---|---|
| Tela preta entre vídeos (double-buffer CSS) | ExoPlayer pré-bufferiza o próximo vídeo nativamente |
| Imagem menor que a tela (object-fit CSS) | ImageView com ScaleType controlado por código |
| Dependência do Chrome 80 do sistema | Nenhuma dependência de browser |
| Instabilidade em TV Box com pouca RAM | ExoPlayer otimizado para dispositivos limitados |
| Service Worker complexo para cache offline | MediaCacheManager: cache simples em filesDir |

---

## 2. Schema do banco (mapeado)

### `devices`
| Coluna | Tipo | Uso no app |
|---|---|---|
| `id` | uuid | Identifica o dispositivo após pairing |
| `device_code` | varchar(6) | Código digitado na tela de pairing (ex: `UBPTXF`) |
| `name` | text | Exibido no HUD discreto do player |
| `playlist_id` | uuid | FK para buscar os itens da playlist |
| `connection_type` | text | App define como `"android"` no pairing |
| `connection_fingerprint` | text | MD5 do ANDROID_ID do dispositivo |
| `last_checkin` | timestamptz | Atualizado a cada 30s pelo HeartbeatManager |
| `is_online` | bool | `true` enquanto app estiver rodando |
| `is_connected` | bool | `true` após pairing bem-sucedido |
| `restart_requested_at` | timestamptz | Dashboard seta para solicitar reset do device |

### `playlist_items`
| Coluna | Tipo | Uso no app |
|---|---|---|
| `id` | uuid | Identificador do item |
| `playlist_id` | uuid | Filtragem dos itens |
| `media_file_id` | uuid | FK para `media_files` (join PostgREST) |
| `order_index` | int | Ordenação da reprodução |
| `duration` | int | Segundos de exibição (imagens); vídeos ignoram |
| `metadata` | jsonb | `{"display_mode":"vertical"}` ou `{}` |

### `media_files`
| Coluna | Tipo | Uso no app |
|---|---|---|
| `file_url` | text | URL do Supabase Storage para download/stream |
| `file_type` | text | `"video"` ou `"image"` |
| `file_extension` | text | `"mp4"`, `"webm"`, `"jpg"` |
| `file_size` | bigint | Valida integridade do cache local |
| `is_vertical` | bool | Detecta conteúdo portrait |
| `width` / `height` | int | Aspect ratio para modo de exibição |

---

## 3. Arquitetura de componentes

```
┌─────────────────────────────────────────────────────────────────┐
│                         ImpulsoTV v3                            │
│                                                                 │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                    MainActivity                          │   │
│  │                                                          │   │
│  │  [PAIRING]              [PLAYER]                         │   │
│  │  EditText               ExoPlayer (vídeo)                │   │
│  │  6 chars                ImageView + Coil (imagem)        │   │
│  │      │                  Crossfade 400ms                  │   │
│  │      │                  Loop infinito                    │   │
│  └──────┼──────────────────────────┬───────────────────────┘   │
│         │                          │                            │
│  SupabaseClient              RealtimeClient                     │
│  (REST / OkHttp)             (WebSocket Phoenix)                │
│         │                          │                            │
│  ┌──────▼──────┐           ┌───────▼──────┐                    │
│  │  Supabase   │           │  Supabase    │                    │
│  │  REST API   │           │  Realtime    │                    │
│  │ (PostgREST) │           │  (Phoenix WS)│                    │
│  └─────────────┘           └──────────────┘                    │
│                                                                 │
│  PlayerService (ForegroundService) ← WakeLock + Activity monitor│
│  BootReceiver → inicia Service + Activity no boot              │
│  MediaCacheManager → cache local em filesDir/media/            │
│  HeartbeatManager → PATCH last_checkin a cada 30s              │
│  SessionManager → SharedPreferences: device_id, playlist_id    │
└─────────────────────────────────────────────────────────────────┘
```

---

## 4. Fluxo de pairing (invertido)

```
Dashboard (browser)              TV Box (Kotlin)
───────────────────              ───────────────
Cria dispositivo
Exibe código: "UBPTXF"

                    ←── usuário vê o código no dashboard
                    ←── usuário digita "UBPTXF" na TV

                         GET /rest/v1/devices
                           ?device_code=eq.UBPTXF
                           &select=*
                                │
                         device encontrado ──► salva sessão local
                                │
                         PATCH /rest/v1/devices
                           ?id=eq.UUID
                           connection_type="android"
                           connection_fingerprint=MD5(ANDROID_ID)
                                │
                         carrega playlist ──► inicia reprodução
                         conecta Realtime
                         inicia Heartbeat
```

---

## 5. Fluxo de reprodução da playlist

```
Itens carregados e ordenados por order_index
         │
         ▼
    playCurrentItem()
         │
    ┌────┴────┐
    │ video?  │ sim → ExoPlayer.setMediaItem() + play()
    └────┬────┘      Listener: onPlaybackStateChanged(ENDED) → advanceToNext()
         │ não
         ▼
    imageView + Coil.load(url)
    Handler.postDelayed(advanceToNext, duration_ms)
         │
         ▼
    advanceToNext()
    ├── pendingPlaylistReload? → reloadPlaylist() → volta ao índice 0
    └── currentIndex = (currentIndex + 1) % size → playCurrentItem()
```

### Transição sem tela preta

```
Vídeo → Vídeo:   ExoPlayer gerencia internamente (pré-buffer)
Vídeo → Imagem:  crossfade() 400ms (alpha animation)
Imagem → Vídeo:  crossfade() 400ms (alpha animation)
Imagem → Imagem: crossfade() 400ms (alpha animation)
```

### Display mode por item

| `metadata.display_mode` | `is_vertical` | Resultado |
|---|---|---|
| `"vertical"` | qualquer | FIT (pillarbox — sem corte) |
| vazio/nulo | `true` | FIT (conteúdo portrait na TV landscape) |
| vazio/nulo | `false` | ZOOM/FILL (preenche a tela) |

---

## 6. Realtime — eventos monitorados

### Canal único: `realtime:impulsotv-{device_id}`

Escuta dois tipos de mudança em um único join WebSocket:

**1. UPDATE em `devices` WHERE `id = device_id`**
```
Quando: dashboard altera restart_requested_at
Ação:   limpa sessão local → mostra tela de pairing
```

**2. * em `playlist_items` WHERE `playlist_id = playlist_id`**
```
Quando: item adicionado, removido, reordenado, duração alterada
Ação:   pendingPlaylistReload = true
        → ao terminar o item atual → recarrega do Supabase → reinicia do item 0
```

---

## 7. Cache local de mídia

```
Diretório: /data/data/com.impulsolab.impulsotv/files/media/

Nomenclatura: MD5(file_url).[ext]
Exemplo: a3f1b2c4d5e6f7a8.mp4

Estratégia:
  1. getPlayableUri(item):
     └── arquivo existe e tamanho bate? → "file:///..." (local)
     └── não? → URL remota (Supabase Storage) enquanto baixa

  2. preCachePlaylist(items):
     └── para cada item, se não em cache → baixa via OkHttp
     └── após download → cleanupOrphanedFiles() remove arquivos não usados

  3. Na tela de pairing (reset):
     └── clearAll() limpa todos os arquivos
```

---

## 8. RLS Policies necessárias

O app usa a **anon key** do Supabase. As seguintes políticas precisam existir:

```sql
-- devices: leitura por device_code (pairing)
CREATE POLICY "anon_select_device_by_code"
ON public.devices FOR SELECT
TO anon
USING (true);  -- ou: USING (device_code IS NOT NULL)

-- devices: atualizar status de conexão e heartbeat
CREATE POLICY "anon_update_device_connection"
ON public.devices FOR UPDATE
TO anon
USING (true);

-- playlist_items: leitura para reprodução
CREATE POLICY "anon_select_playlist_items"
ON public.playlist_items FOR SELECT
TO anon
USING (true);

-- media_files: leitura dos metadados
CREATE POLICY "anon_select_media_files"
ON public.media_files FOR SELECT
TO anon
USING (true);
```

> **Nota de segurança:** As políticas acima são permissivas. Se quiser restringir acesso,
> substitua `USING (true)` por condições mais específicas, como limitar por `user_id`
> ou exigir que o `device_code` seja válido antes de permitir SELECT em `playlist_items`.

---

## 9. Como configurar e instalar

### 1. Configurar credenciais

Em `app/build.gradle`, substitua:
```gradle
buildConfigField "String", "SUPABASE_ANON_KEY", '"SUA_CHAVE_ANON_AQUI"'
```
com sua chave anon do Supabase Dashboard → Settings → API.

### 2. Build e instalação

```bash
# Build debug
./gradlew assembleDebug

# Instalar via ADB
adb connect <IP_DO_TV_BOX>:5555
adb install app/build/outputs/apk/debug/app-debug.apk

# Ver logs em tempo real
adb logcat -s ImpulsoTV.Main ImpulsoTV.Player ImpulsoTV.Supabase \
              ImpulsoTV.Realtime ImpulsoTV.Cache ImpulsoTV.Heartbeat
```

### 3. Definir como launcher padrão (kiosk total)

```bash
adb shell cmd package set-home-activity \
  com.impulsolab.impulsotv/.ui.MainActivity
```

### 4. Parear um dispositivo

1. Abra o dashboard web
2. Vá em Dispositivos → copie o `device_code` (ex: `UBPTXF`)
3. Na TV Box, o app abre na tela de pairing
4. Digite o código → Enter ou aguarde auto-conectar

---

## 10. Navegação no código por tags

| Tag | Arquivo | O que é |
|---|---|---|
| `[STATE]` | MainActivity | Transições PAIRING ↔ PLAYER |
| `[PAIRING-UI]` | MainActivity | Lógica de digitação e validação do código |
| `[PLAYER-INIT]` | MainActivity | Configuração do ExoPlayer |
| `[PLAYLIST-LOOP]` | MainActivity | Ciclo de reprodução sequencial e loop |
| `[ITEM-VIDEO]` | MainActivity | Reprodução de vídeo via ExoPlayer |
| `[ITEM-IMAGE]` | MainActivity | Exibição de imagem via Coil + timer |
| `[TRANSITION]` | MainActivity | Crossfade entre itens |
| `[REALTIME]` | MainActivity | Handlers de eventos Supabase Realtime |
| `[REALTIME-PROTOCOL]` | RealtimeClient | Protocolo Phoenix WebSocket |
| `[RESTART]` | RealtimeClient | Detecção do comando restart_requested_at |
| `[PLAYLIST-UPDATE]` | RealtimeClient | Detecção de mudanças na playlist |
| `[PAIRING]` | SupabaseClient | Queries de pairing (findDeviceByCode) |
| `[PLAYLIST]` | SupabaseClient | Query de itens com join media_files |
| `[ACTIVITY-MONITOR]` | PlayerService | Ping/pong de saúde da Activity |
| `[BOOT-SEQUENCE]` | BootReceiver | Ordem de boot: Service → Activity |

---

*ImpulsoTV v3 — Player nativo sem WebView, construído sobre Supabase + ExoPlayer + Kotlin Coroutines.*
