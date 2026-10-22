# Sinuca Bluetooth

[![Android CI](https://github.com/ldmaster/sinuca-bluetooth/actions/workflows/android.yml/badge.svg)](https://github.com/ldmaster/sinuca-bluetooth/actions/workflows/android.yml)

Jogo de sinuca 8-ball **nativo para Android**, para exatamente **dois aparelhos**
conectados por **Bluetooth clássico (RFCOMM)**. A mesa e o taco são modelos 3D
gerados no **Blender** com o harness `blender-forge`; o jogo é
Kotlin + OpenGL ES 3.0, sem engine externa.

![Mesa e HUD](docs/screenshot.png)

## O que está implementado

- Mesa 3D com seis caçapas, bola branca, 15 coloridas e taco animado.
- Física própria de sinuca: atrito de rolamento, colisões elásticas entre bolas,
  quiques nas tabelas e captura nas caçapas, com passo fixo e sub-passos.
- Regras de 8-ball simplificadas: grupos fixos (1–7 x 9–15), continuidade de vez
  ao encaçapar a própria bola, falta da branca, vitória/derrota pela 8.
- Multiplayer host-autoritativo: o host simula e envia snapshots (~16 Hz); o
  cliente prevê localmente e recebe correções. Apenas uma conexão é aceita.
- Fluxo Bluetooth: criar partida (visível por 5 min) ou entrar em partida
  (aparelhos pareados + descoberta).
- Modo **Treino local** para jogar sozinho e testar a física sem Bluetooth.
- HUD com placar, indicador de vez, mira por toque, barra de força e botão
  ATIRAR.

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

Controles (paisagem):

- **Mira:** arraste o dedo sobre a mesa.
- **Força:** arraste na barra inferior (0–100%).
- **Tacada:** toque em **ATIRAR**.
- **Sair:** botão **SAIR** no topo.

## Regras adotadas

- Jogador 1 é sólidas (1–7); jogador 2 é listradas (9–15). A 8 vale para ambos.
- Encaçapar uma bola do próprio grupo mantém a vez; não encaçapar nada passa a vez.
- Branca na caçapa: falta, a vez passa e a branca volta ao ponto inicial.
- A 8 antes de limpar o grupo elimina o jogador; após limpar o grupo, vence.
- Sem efeito (english), sem bola na mão livre e sem regra de tacada de saída
  completa — é um protótipo jogável, não uma simulação oficial.

## Arquitetura

```
app/src/main/java/com/ldmaster/sinuca/
├── MainActivity.kt          menu, permissões, descoberta e conexão Bluetooth
├── GameActivity.kt          ciclo do jogo, sincronização e entrada
├── game/
│   ├── Table.kt             dimensões, caçapas e pontos fixos (metros)
│   ├── Ball.kt
│   └── GameWorld.kt         física, regras, snapshots
├── net/
│   ├── BluetoothGame.kt     RFCOMM: accept, connect, leitura/escrita
│   ├── Wire.kt              protocolo JSON por linha
│   └── Session.kt
├── render/
│   ├── GlbLoader.kt         parser GLB (glTF 2.0) → VBO com cores
│   └── GameRenderer.kt      OpenGL ES 3.0: mesa, bolas, taco e mira
└── ui/HudView.kt            HUD e controles por toque
```

O protocolo usa uma linha JSON por mensagem (`hello`, `start`, `state`, `shot`,
`rematch`, `bye`) sobre um socket RFCOMM com o UUID
`8f3e1c2a-5b7d-4e9f-9a10-3c2b1d4e5f60`. O host é a autoridade: o cliente envia
`shot` e recebe `state`.

## Assets 3D (Blender via forge)

Os planos ficam em `assets-plans/table.json` e `assets-plans/cue.json` e usam apenas
operações tipadas do harness (sem scripts `bpy`). Para regerar:

```bash
forge apply assets-plans/table.json --profile prop --brief "low-poly billiards table"
forge apply assets-plans/cue.json   --profile prop --brief "low-poly pool cue"
cp var/runs/<run>/blender/artifacts/table.glb app/src/main/assets/models/table.glb
cp var/runs/<run>/blender/artifacts/cue.glb   app/src/main/assets/models/cue.glb
```

Cada execução grava `.blend`, preview PNG e GLB em `var/runs/<id>/blender/`.
O `GlbLoader` aplica as transformações de nó e as cores dos materiais (PBR base
color), sem depender de Filament/Sceneform.

## Testes

```bash
./gradlew :app:testDebugUnitTest
```

Cobrem colisão frontal, quique na tabela, queda em caçapa, atrito, regras de vez,
8-ball e ida/volta do protocolo.

## Limitações conhecidas

- Bluetooth não funciona no emulador; o multiplayer só pode ser validado em dois
  aparelhos reais.
- Sem áudio, sem revanche automática e sem seleção de grupos na primeira bola.
- O cliente prevê a física localmente e é corrigido pelo host; em Bluetooth muito
  instável pode haver pequenos saltos.
- Os modelos são low-poly e sem texturas, adequados a um protótipo.
