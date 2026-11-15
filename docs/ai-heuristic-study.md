# IA local · Estudo da heurística de tacada

Este estudo descreve **como a IA escolhe a tacada hoje**, mede o que o benchmark
cobre, aponta onde a heurística perde força no jogo real e propõe uma ordem de
otimização. O código vive em `app/src/main/java/com/ldmaster/sinuca/game/AiPlayer.kt`
(`AiPlayer` + `AiPlanner`); as regras que a IA assume estão em `docs/ai-playbook.md`.

---

## 1. Fluxo completo

```
GameActivity.sync (UI thread, 200 ms)
  └─ AiPlayer.tick()
       ├─ world.snapshot()            (imutável, sob o lock do mundo)
       ├─ resolvePending(...)         (aprendizado do resultado anterior)
       └─ executor (thread "sinuca-ai")
            └─ AiPlanner.plan(snapshot, player, budgetMillis)
```

1. `tick()` só age quando `winner < 0`, `!moving`, `turn == player` e passou o
   cooldown de 350 ms.
2. O plano roda numa única thread de trabalho; a UI nunca bloqueia.
3. `plan()` aplica o snapshot num `GameWorld` próprio (`simulation`) e nunca
   mexe no mundo real.
4. Depois do plano, o nível segura o badge "IA PENSANDO" até
   `thinkTimeMillis` (5,4 s no Extremo, dos quais até 4,8 s podem ser busca).
   Isso é apresentação, não força.

## 2. Geração de candidatos (geometria)

Para **cada bola do grupo** (`1..7` / `9..15`; a `8` quando o grupo acabou) e
**cada uma das 6 caçapas**:

1. Calcula a **ghost ball**: centro da branca no contato, a `2R` do centro do
   alvo, na direção oposta à caçapa.
2. Rejeita se:
   - a ghost saiu do retângulo de jogo;
   - o corte é fino demais: `cos(cut) < 0.20` (≈ 78,5°);
   - a linha branca→ghost ou alvo→caçapa está bloqueada por outra bola
     (`distance < 2R`, ignorando a branca e o próprio alvo).
3. Estima a força por distância percorrida:
   `basePower = clamp(0.26 + 0.55 * (|branca→alvo| + |alvo→caçapa|) / 3, 0.28, 0.85)`
   e gera 3 potências: `base ± 0.10`.
4. Ordena por uma heurística barata:

```
heuristic = (1 - cosCut) * 140 + needed * 18 + (corner ? 6 : 0) + |power - base| * 2
```

Limite de candidatos por nível: Fácil 5, Médio 14, Difícil 40, Extremo 110.
Com 7 bolas são `7 × 6 × 3 = 126` candidatos geométricos.

## 3. Avaliação por simulação (o coração)

Os melhores candidatos pela heurística são **jogados na física real**:

```kotlin
simulation.applySnapshot(snapshot)
simulation.shoot(angle, power)          // strikeX = strikeY = 0
settle()                                // até 900 passos de 1/120 s
```

O score atual (`simulateAndScore`):

| Termo | Peso | Efeito |
|---|---:|---|
| branca encaçapada | −4000 | evita scratch |
| alvo encaçapado | +900 | pot do alvo |
| bolas do grupo encaçapadas | +250 cada | pot |
| bolas extras (2ª, 3ª…) | +`multiPotBonus` (Extremo 320) | multi-pot |
| bola do adversário encaçapada | −220 cada | evita dar presente |
| mantém a vez | +350 | continua jogando |
| **próxima bola** (posição) | até +240 | `(melhor corte limpo − 0.2) * 300` |
| branca colada no trilho | até −108 | `(0.12 − distância) * 900` |
| corte fino | `(1−cosCut) * 80` | prefere corte reto |
| memória aprendida | `(p − 0.45) * learningWeight * 600` | ajuste por família |

O termo de **posição** foi implementado nesta rodada: depois de cada
simulação, `nextShotQuality` procura a linha limpa mais reta em qualquer bola
do grupo na mesa (ou na 8, se o grupo acabou) e pontua. O antigo bônus por
"branca perto do trilho" virou penalidade — colar a branca no trilho não é
posição, é o próximo problema.

Depois: ordena, refina localmente os 6 melhores (3 rodadas de ângulo
`±1,2° / ±0,5° / ±0,2°` × potência `±0,08 / ±0,04 / ±0,02`), e se a melhor
tacada não encaçapou nada, tenta `planSafety`.

### Custo

- Cada passo de física integra 16 bolas e resolve colisões em O(n²): ~136
  pares.
- Uma tacada típica assenta em 2–4 s simulados, ou seja ~240–480 passos.
- Extremo: até 110 simulações + ~108 simulações de refinamento.
- Pior caso grosseiro: 218 × 480 × 136 ≈ **14 milhões de checagens de par**,
  mais `pathClear` (16 bolas × 12 linhas por candidato) no início.
- O deadline é checado **entre** candidatos, não dentro de `settle()`; uma
  tacada longa pode estourar o orçamento em até 900 passos.

## 4. Segurança

`planSafety` testa 17 ângulos (até 3 alvos × ângulo base ±5°, mais 8 direções) × 2 potências
(0,22 / 0,30) e pontua:

```
400 - (tacadas limpas do adversário) * 320
    + (bolas do adversário escondidas) * 70
    + (branca perto do trilho) * 1500
    + memória de segurança
```

Limitações: não verifica se o **primeiro contato** foi legal, não passa efeito,
não mede a qualidade da próxima tacada do adversário (só conta linha limpa) e
o próprio motor não pune tocar no grupo errado primeiro — então uma "segurança"
pode ser falta sem a IA perceber.

## 5. Aprendizado

`AiMemory` guarda uma média suavizada por célula de
`ShotFeatures(cutCos, distance, power)` com `5 × 4 × 3 = 60` células por nível.
É atualizada só quando uma tacada da IA assenta. O peso da memória no score é
`learningWeight` (Difícil 1,0 / Extremo 1,6). Com poucas amostras, o prior 0,45
domina; com muitas, a célula pode reforçar um viés ruim porque **não distingue
caçapa nem posição da branca**.

## 6. O que o benchmark mede hoje

Testes em `app/src/test/java/com/ldmaster/sinuca/game/`:

| Teste | Layout | Resultado atual |
|---|---|---|
| `AiStrengthTest` | 10 layouts com **1 alvo e a branca** | Difícil **10/10** |
| `AiExtremeTest` | os mesmos 10 layouts | Extremo **10/10** |
| `AiTrainingTest` | pacote de 12 cenários | pot **8/9**, legal **2/2**, oito **1/1**, scratch **0** |

Ou seja, **o benchmark está saturado**. Ele mede tacadas isoladas e limpas, que
são exatamente o ponto forte do simulador. Não mede:

- posição depois do pot (a bola seguinte ficou jogável?);
- tacadas em cluster/combinação;
- segurança contra um adversário que realmente encaçapa;
- sequência (run-out) em uma visita;
- uso de efeito.

É por isso que o Extremo parece fraco em jogo apesar de 10/10 no teste.

## 7. Onde a heurística perde força no jogo real

### 7.1 Jogo de posição (implementado nesta rodada)

O score não olhava para a **próxima** bola; o único termo de mesa premiava a
branca colada no trilho, que costuma ser o pior lugar para a tacada seguinte.
Agora `nextShotQuality` avalia a melhor linha limpa depois do assentamento e o
trilho passou a ser penalizado. Ainda falta escolher a posição **de propósito**
(hoje é uma consequência da física), mas o "encaçapa e volta sem nada" caiu.

### 7.2 O candidato é avaliado por um "make" determinístico

A física é determinística: encaçapar por 1 mm ou por 10 cm dá o mesmo score.
Não há medida de **robustez** (variar a mira ±0,2° e ver quantas vezes ainda
encaçapa). O refinamento acha um ponto de make, não uma faixa de make.

### 7.3 O espaço de busca é só pot direto

Só existem candidatos de ghost ball direto. Não há:
- combinações (bola A empurra B na caçapa);
- tabelas/kicks quando não há linha direta;
- tacadas de duas tabelas;
- escolha de caçapa por **margem** (usa-se a mesma ghost para qualquer corte).

### 7.4 Efeito nunca é usado

`simulation.shoot(angle, power)` chama com `strikeX = strikeY = 0` em todos os
pontos. A IA não consegue:
- puxar (draw) para voltar da caçapa;
- seguir (follow) para abrir ângulo;
- corrigir a saída com side.

O motor de física já suporta os três (`Ball.spinSide`, `spinForward`,
`DRAW_FOLLOW_GAIN`, `SIDE_SPIN_CURVE`); falta o planejador explorar.

### 7.5 Orçamento gasto na ordem errada

A lista é ordenada pela heurística barata, que não se correlaciona bem com o
score simulado. O extremo pode gastar o orçamento em 110 candidatos medianos e
nunca simular a combinação que valia a pena. E o deadline não interrompe uma
simulação em andamento.

### 7.6 Segurança otimiza o proxy errado

`cleanShots` conta "linha limpa", mas não a **dificuldade** da linha. Uma bola
com linha limpa e corte de 75° conta igual a uma bola na boca. Além disso, a
segurança não valida legalidade do primeiro contato nem o scratch de forma
completa, e o motor não pune falta de grupo — então o proxy e a regra divergem.

### 7.7 A memória aprende a família errada

Uma célula `(corte, distância, potência)` não sabe se a tacada foi de canto ou
central, nem onde a branca estava. O reforço pode favorecer uma tacada que só
funciona em um canto específico.

## 8. Roadmap de otimização (por impacto × esforço)

### Prioridade 1 — posição (implementada)

`nextShotQuality` pontua a melhor linha limpa restante depois do assentamento
e a branca no trilho passou a ser penalizada (ver §3). O custo é
~7 bolas × 6 caçapas × 2 `pathClear` por candidato simulado.

O que ainda falta nesta frente: **controlar** a posição, não só avaliá-la.
Isso depende do efeito (Prioridade 3): hoje a branca termina onde a física
mandar; com draw/follow a IA pode escolher *onde* terminar. Uma segunda
melhoria barata é penalizar a branca perto de outra bola (`< 3R`) no score.

### Prioridade 2 — robustez

Para os 5 melhores, simule também `angle ± 0,15°` e `power ± 0,03` e use a
**taxa de pot** (0..1) em vez do make binário. Isso também elimina o "make por
1 mm" e melhora a consistência no aparelho.

### Prioridade 3 — efeito na busca

Adicione `strikeY ∈ {−0.4, 0, +0.5}` (draw/follow) aos finalistas e deixe o
simulador escolher a posição. Requer passar `strikeX/strikeY` no `AiShot` até
`GameActivity` (hoje o disparo da IA chama `world.shoot(angle, power, 0, 0)`).

### Prioridade 4 — combinações e tabelas

Gere candidatos extras:
- **combinação**: para cada par (A, B) com B perto de uma caçapa, aponte a
  branca para a ghost de A na direção de B;
- **tabela**: espelhe a caçapa no trilho mais próximo e aponte para a ghost
  refletida; valide a linha com `pathClear`.

Ordene esses candidatos pela mesma probabilidade e simule os melhores.

### Prioridade 5 — orçamento e ordem

- Cheque o deadline **dentro** do loop de `settle()`.
- Ordene por uma probabilidade simples
  `p ≈ exp(-k * (1 - cosCut)) * exp(-m * distance) * potênciaÓtima`
  em vez da heurística linear atual.
- Guarde uma fração do orçamento (ex.: 35%) para o refinamento.

### Prioridade 6 — segurança e memória

- Exija `cue.active` e o primeiro contato no grupo; se o motor ganhar um flag
  de primeiro contato, use-o.
- Troque "linha limpa" por "probabilidade do adversário" (mesma função de
  corte/distância).
- Aumente a célula de memória com o tipo de caçapa e a distância da branca ao
  trilho, ou use um modelo linear de 3–4 features; reduza o peso até ter N
  amostras por célula.

### Prioridade 7 — medir o que importa

Adicionar testes de **sequência**:

1. `run-out`: uma mesa com 3 bolas do grupo + 8; a IA deve limpar em uma visita
   (ou chegar a 2 pots seguidos) em ≥ 60% dos layouts.
2. `position`: depois do pot, existe pelo menos uma tacada com `cosCut > 0.5`.
3. `safety-vs-potter`: adversário virtual que encaçapa a melhor bola; medir
   quantas visitas ele converte depois da segurança.
4. `cluster`: 2 bolas do grupo agrupadas; medir pot/segurança.

Sem esses testes, qualquer ajuste de score parece "melhor" e o benchmark
continua 10/10.

## 9. Resumo executivo

- A IA **simula a física de verdade** e escolhe bem uma tacada isolada; por
  isso o benchmark satura em 10/10.
- O que falta é **planejamento de sequência**: posição, robustez, efeito,
  combinações e uma segurança que meça a ameaça real.
- O maior retorno por esforço é o **termo de posição** (Prioridade 1), seguido
  de **robustez** (2) e **deadline dentro do settle** (5).
- O benchmark atual não mede nada disso; a Prioridade 7 é pré-requisito para
  saber se as mudanças realmente deixaram o Extremo mais forte.
