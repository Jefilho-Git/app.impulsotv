# Pedido à TI — rede isolada para as TVs de sinalização (ImpulsoTV)

**Assunto:** Rede separada para as TV Boxes do ImpulsoTV

Olá, equipe de TI.

As TVs de sinalização digital do hospital usam TV Boxes **Proeletronic PROSB3000 (Android 10)**
com o app ImpulsoTV. O firmware desse modelo vem de fábrica com a **depuração ADB pela rede ligada
e sem autenticação** (porta TCP **5555**). Hoje elas estão na rede `HSV-Wireless`, junto com os demais
aparelhos.

**Risco:** qualquer dispositivo na mesma rede pode se conectar a essas TVs pela porta 5555 e instalar
aplicativos ou alterar o que aparece na tela, sem confirmação no aparelho.

**Pedido:**
1. Colocar as TV Boxes numa **rede/VLAN própria** (ou SSID exclusivo), com **isolamento entre clientes**.
2. Nessa rede, liberar **apenas a saída para a internet** (HTTPS 443), necessária para:
   - `*.supabase.co` — conteúdo, status e atualizações em tempo real (HTTPS e WebSocket)
   - `impulsotv.impulsolab.com.br` — atualizações do aplicativo
3. **Bloquear o acesso de entrada** às TVs a partir das outras redes (em especial a porta **5555**).

**Importante:** a porta 5555 deve continuar **ligada dentro de cada TV**, porque o próprio app a usa
localmente (127.0.0.1) para se atualizar e manter o Wi‑Fi ativo. O bloqueio é só para o acesso
**vindo de outros aparelhos**.

Se preferirem manter na rede atual, como alternativa mínima: bloquear a porta TCP 5555 entre os
clientes do Wi‑Fi.

Obrigado!
