# Sinuca Bluetooth

[![Android CI](https://github.com/ldmaster/sinuca-bluetooth/actions/workflows/android.yml/badge.svg)](https://github.com/ldmaster/sinuca-bluetooth/actions/workflows/android.yml)

Jogo de sinuca 8-ball **nativo para Android**, para exatamente **dois aparelhos**
conectados por **Bluetooth clássico (RFCOMM)**. A mesa e o taco são modelos 3D
gerados no **Blender** com o harness `blender-forge`; as bolas usam esferas de
alta tesselação com texturas procedurais de número e listra. O jogo é
Kotlin + OpenGL ES 3.0, sem engine externa.

![Mesa e HUD](docs/screenshot.png)

Vistas extras: [de cima, com as redes](docs/screenshot-top.png) ·
[trajetória prevista](docs/screenshot-trajectory.png).

## O que está implementado

- Mesa de alta definição com caçapas reais: furos na mesa e no apron, poços
  cilíndricos, redes no fundo, aros escuros, almofadas e trilhos largos com
  chanfro. Proporção 2:1 e trilhos grossos, seguindo a mesa de referência.
- Bolas de 48×32 segmentos com textura de número/listra: cores definidas, bolas
  lisas e listradas, 8 e branca.
- Física própria: atrito, colisões, quiques, queda gradual na caçapa e
  **quinas de caçapa**. A captura é calibrada ao diâmetro visível do buraco;
  só bolas realmente rápidas (acima de 4 m/s) raspando a borda quicam na boca.
- **Efeito (english)**: selecione o ponto de acerto na bola branca. Parte baixa
  faz a branca voltar (draw), parte alta segue em frente (follow), laterais
  curvam a trajetória e alteram o quique nas tabelas.
- Indicador em tempo real da bola na mira: anel fantasma no ponto de contato,
  destaque na bola-alvo e seta com a direção que ela vai tomar. A seta usa a
  normal do contato real (bola fantasma), então cortar a quina muda a direção.
- Trajetória completa opcional: simula a tacada com a força, ângulo e efeito
  atuais e desenha o caminho da branca e da primeira bola atingida.
- Mira por arrasto relativo, sem salto para onde o dedo tocou.
- Barra de força vertical discreta, botão de tacada pequeno e HUD sem painéis:
  bolas encaçapadas, vez, ícones de câmera/trajetória/sair e efeito ficam nos
  cantos.
- Três perspectivas de câmera (3/4, alta e baixa).
- Rolamento visual das bolas proporcional à velocidade.
- Multiplayer host-autoritativo: o host simula e envia snapshots (~16 Hz); o
  cliente prevê localmente e recebe correções. Apenas uma conexão é aceita.
- Fluxo Bluetooth: criar partida (visível por 5 min) ou entrar em partida
  (aparelhos pareados + descoberta).
- Modo **Treino local** para jogar sozinho e testar tudo sem Bluetooth.

## Requisitos

- Android 8.0+ (minSdk 26), compilado/targetSdk 36.
- Dois celulares com Bluetooth. **Pareie os dois antes de jogar** nas
  configurações do Android — o app não faz o pareamento sozinho.
- Para compilar: JDK 17+, Android SDK 36 e o wrapper incluído (Gradle 9.4.1).

## Compilar e instalar

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

APK: `app/build/outputs/apk/debug/app-debug.apk` (pacote `com.ldmaster.sinuca`).

## Como jogar

1. Pareie os dois celulares no Android.
2. **Host:** toque em *Criar partida* e aceite ficar visível.
3. **Visitante:** toque em *Entrar em partida* e selecione o outro aparelho.
4. Quando a mesa aparecer, o jogador 1 dá a primeira tacada.

Controles (paisagem, tudo nos cantos):

- **Mira:** arraste sobre a mesa. O movimento é relativo: continue arrastando em
  qualquer lugar para girar mais.
- **Força:** arraste a barra vertical fina na borda direita (para cima aumenta).
- **Efeito:** toque na bolinha **EFEITO** no canto inferior esquerdo; o ponto
  vermelho indica onde a tacada acerta a branca.
- **Tacada:** toque no círculo **ATIRAR** no canto inferior direito.
- **Trajetória:** ícone de curva no canto superior direito (liga/desliga).
- **Câmera:** ícone de câmera no canto superior direito alterna as 3 vistas.
- **Sair:** **X** no canto superior direito.
- **Bolas encaçapadas:** fileiras discretas **V** (você) e **A** (adversário)
  no canto superior esquerdo.

## Regras adotadas

- Jogador 1 é sólidas (1–7); jogador 2 é listradas (9–15). A 8 vale para ambos.
- Encaçapar uma bola do próprio grupo mantém a vez; não encaçapar nada passa a vez.
- Branca na caçapa: falta, a vez passa e a branca volta ao ponto inicial.
- A 8 antes de limpar o grupo elimina o jogador; após limpar o grupo, vence.
- O efeito é uma aproximação jogável (não é um modelo físico completo de tacada);
  não há bola na mão livre nem regra completa de tacada de saída.

## Arquitetura

```
app/src/main/java/com/ldmaster/sinuca/
├── MainActivity.kt          menu, permissões, descoberta e conexão Bluetooth
├── GameActivity.kt          ciclo do jogo, sincronização e entrada
├── game/
│   ├── Table.kt             dimensões, caçapas e pontos fixos (metros)
│   ├── Ball.kt              estado, efeito e matriz visual de rolamento
│   └── GameWorld.kt         física, regras, previsão e snapshots
├── net/
│   ├── BluetoothGame.kt     RFCOMM: accept, connect, leitura/escrita
│   ├── Wire.kt              protocolo JSON por linha
│   └── Session.kt
├── render/
│   ├── GlbLoader.kt         parser GLB (glTF 2.0) → VBO com cores/UVs
│   └── GameRenderer.kt      GLES 3.0: mesa, bolas texturizadas, mira,
│                            indicador de direção e trajetória prevista
└── ui/HudView.kt            HUD compacto, força, efeito e botões
```

O protocolo usa uma linha JSON por mensagem (`hello`, `start`, `state`, `shot`,
`rematch`, `bye`) sobre um socket RFCOMM com o UUID
`8f3e1c2a-5b7d-4e9f-9a10-3c2b1d4e5f60`. O host é a autoridade: o cliente envia
`shot` (ângulo, força e ponto de efeito) e recebe `state`.

## Assets 3D (Blender via forge)

Os planos ficam em `assets-plans/table.json` e `assets-plans/cue.json` e usam apenas
operações tipadas do harness (sem scripts `bpy`). A mesa é construída com
booleanos (furos da mesa e do apron), poços, redes, aros escuros, almofadas,
trilhos largos com chanfro e proporção 2:1. Para regerar:

```bash
forge apply assets-plans/table.json --profile prop --brief "high detail billiards table with real pockets and nets"
forge apply assets-plans/cue.json   --profile prop --brief "low-poly pool cue"
cp var/runs/<run>/blender/artifacts/table.glb app/src/main/assets/models/table.glb
cp var/runs/<run>/blender/artifacts/cue.glb   app/src/main/assets/models/cue.glb
```

Cada execução grava `.blend`, previews PNG e GLB em `var/runs/<id>/blender/`.
O `GlbLoader` aplica as transformações de nó e as cores dos materiais (PBR base
color). As texturas das bolas são geradas no aparelho com Canvas e enviadas como
texturas OpenGL, sem depender de Filament/Sceneform.

## Testes

```bash
./gradlew :app:testDebugUnitTest
```

Cobrem colisão frontal, quique na tabela, queda em caçapa (incluindo a animação),
quinas de caçapa, geometria de mira (centro x quina), draw/follow/english,
atrito, regras de vez, 8-ball, sincronização host/cliente e ida/volta do
protocolo (30 testes).

## Limitações conhecidas

- Bluetooth não funciona no emulador; o multiplayer só pode ser validado em dois
  aparelhos reais.
- Sem áudio, sem revanche automática e sem seleção de grupos na primeira bola.
- O cliente prevê a física localmente e é corrigido pelo host; em Bluetooth muito
  instável pode haver pequenos saltos.
- O efeito e as quinas de caçapa são modelos simplificados, calibrados para a
  jogabilidade.
