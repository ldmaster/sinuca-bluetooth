# Otimizações para Adreno / Snapdragon

> O link do estudo externo (`artifacts.commandcode.ai/...`) exige login e não
> pôde ser aberto nesta sessão. As decisões abaixo vêm da leitura do caminho
> quente do renderizador e de práticas padrão para Adreno (GPU tile-based,
> GLES 3.0, orçamento térmico/banda).

## 1. Perfil do que roda por frame

`GameRenderer.onDrawFrame`:

1. `world.step(dt)` — física na thread de render.
2. `updateCharacters(dt)` — poses, caminhada, IK já assado.
3. **Shadow pass** (2048² em hardware): mesa, 16 bolas, 2 personagens com skin.
4. **Scene pass** em alvo offscreen RGBA16F + MSAA: mesa, bolas, 2 personagens,
   taco, overlay de mira.
5. Resolve do MSAA (`glBlitFramebuffer`).
6. **Post pass**: bright + 3 níveis de blur + composite (bloom, tonemap,
   vinheta, grão).

Custos dominantes em um Snapdragon: fill-rate do alvo HDR/MSAA, banda do
resolve, e — no lado da CPU — o skinning dos personagens e o lixo de alocação
por frame.

## 2. O que foi mudado

### 2.1 Skinning sem alocação (`SkinnedModel.kt`)

Antes, cada frame e cada personagem alocava:

- 3 arrays por nó (`t`, `r`, `s` do bind) → 66 arrays;
- 1 `FloatArray` por matriz local (`trs`);
- 1 `FloatArray` por multiplicação de matriz (22 em `updateGlobals`);
- 1 `FloatArray` por junta em `computeJoints` (20);
- 1 `FloatArray` por canal amostrado e por `slerp` (~40).

Com dois personagens e 60 fps isso passava de **15 mil alocações por segundo**,
o que no Android significa GC frequente e jank.

Agora:

- `trsInto` escreve direto em `node.local`;
- `sampleInto`/`slerpInto` escrevem em um `poseValue` reutilizado;
- `Mat4.multiplyInto` preenche as matrizes existentes, inclusive com offsets
  para a matriz inversa de bind;
- o bind é restaurado com `System.arraycopy`.

Resultado: **zero alocação** no caminho de pose por frame (fora do carregamento).

### 2.2 Overlay de mira sem buffer novo (`GameRenderer.kt`)

`drawLineVertices` alocava um `ByteBuffer.allocateDirect` a cada chamada — até
4 por frame (dashes, indicador, predição). Buffers diretos são caros e geram
pressão no GC nativo. Agora há um único `FloatBuffer` reaproveitado que cresce
só quando necessário.

### 2.3 Texturas de bola 4× menores (`GameRenderer.kt`)

As 16 texturas passaram de 1024×512 para 512×256 (RGBA8 + mipmaps):
**~43 MB → ~11 MB de VRAM**. Uma bola ocupa poucas dezenas de pixels na câmera
do jogo; não há perda visível. Menos VRAM também reduz a pressão de textura em
aparelhos com 4 GB.

### 2.4 Cache de recursos já existente (mantido)

- `uBones`, `uMvp`, etc. já eram resolvidos uma vez;
- `PostFx` e `SceneTarget` já reutilizam FBOs/texturas;
- a IA e a predição de trajetória já rodam em threads de trabalho;
- o modo software já reduz shadow map e MSAA.

## 3. Alavancas restantes (não aplicadas para não mexer na qualidade)

### 3.1 Render scale do alvo offscreen

Em telas 1440p o alvo RGBA16F + 4× MSAA chega a ~150 MB só de cor. Renderizar a
cena a 0,75–0,85× e deixar o composite upscalar é o maior ganho de fill-rate.
Ponto de entrada: `onSurfaceChanged` → `sceneTarget.create(...)` e
`post.create(...)`. O aspect ratio deve ser preservado. Sugestão: expor uma
constante `SCENE_RENDER_SCALE = 0.85f` e testar num aparelho real.

### 3.2 MSAA

`pickSamples()` devolve 4 em hardware. Para Adreno, 2× + o bloom/tonemap já
esconde serrilhado e reduz a banda do resolve pela metade. Basta trocar o
`when` de `pickSamples`. É uma decisão de qualidade, não de código.

### 3.3 Shadow map

2048² em hardware é conservador. 1024² com o filtro atual (1 tap + bias) é
suficiente para a sombra suave da mesa; o modo software já usa 1024.

### 3.4 Bloom em resolução da cena

`post.create(width, height)` usa a resolução da *surface*. Se o render scale for
aplicado, passar a resolução da cena reduz os 3 níveis de blur na mesma
proporção.

### 3.5 `GL_EXT_multisampled_render_to_texture`

Adreno suporta a extensão, que resolve o MSAA no tile sem o
`glBlitFramebuffer` explícito. Exige carregar o entry point via
`eglGetProcAddress`/reflexão; é a otimização de banda mais agressiva depois do
render scale.

## 4. Como medir num aparelho real

- `adb shell dumpsys gfxinfo com.ldmaster.sinuca framestats`
- GPU: Snapdragon Profiler (System Profiler → GPU Busy, Texture, ALU/Bandwidth)
- CPU: `simpleperf` ou o Android Studio CPU Profiler com `sinuca-ai` e a thread
  de render.
- Comparar antes/depois com a mesma câmera parada e a mesma cena (a cena inicial
  com o robô andando é um bom loop).

O emulador deste projeto é **software (SwiftShader)**, então o tempo de frame
dele não representa Adreno; ele serve para correção visual, não para tuning de
GPU.
