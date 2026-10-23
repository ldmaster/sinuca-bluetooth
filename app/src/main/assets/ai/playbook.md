# IA local · Playbook e treino offline

Este documento é a “constituição” do oponente local. Ele explica **como cada nível deve pensar**, o que a IA pode e não pode fazer, e como treinar/medir o Difícil offline sem internet.

O arquivo de cenários fica em `app/src/test/resources/ai_training.json` (cópia em `app/src/main/assets/ai/training.json`). O teste é `AiTrainingTest`; o benchmark rápido é `AiStrengthTest`.

---

## 1. Regras que a IA precisa respeitar

- O alvo é sempre uma bola do próprio grupo (sólidas 1–7 / listradas 9–15). Depois de limpar o grupo, o alvo é a **8**.
- A **branca na caçapa** é falta: a vez passa. Nunca vale a pena encaçapar a branca para “fazer média”.
- Uma tacada legal precisa **tocar primeiro uma bola do próprio grupo** (ou a 8, quando for a hora).
- A 8 antes de limpar o grupo perde o jogo. A 8 depois de limpar o grupo vence.
- A captura acontece na **linha da boca**; o buraco está 25 mm atrás, no trilho. A bola pode rolar pela prateleira.
- O quique na almofada é K-66 (restituição 0,88); o efeito (draw/follow/side) altera a saída.
- Sem bola na mão livre e sem regra completa de tacada de saída: a IA joga com o que está na mesa.

## 2. Hierarquia de decisão (todos os níveis)

1. **Legalidade** — a primeira bola tocada é do grupo certo.
2. **Não entregar a partida** — evitar branca na caçapa, evitar deixar a 8 fácil para o adversário.
3. **Encaçapar** — escolher a caçapa com maior margem, não a mais bonita.
4. **Posição** — deixar a branca com ângulo para a próxima bola; evitar trilho e clusters.
5. **Segurança** — se não há pot real, esconder a branca, afastar a bola-alvo da caçapa e não dar falta.

A ordem importa: uma posição bonita que não encaçapa vale menos que uma bola fácil encaçapada. Uma segurança que dá falta vale menos que uma tacada legal sem pot.

## 3. Fácil

**Objetivo:** parecer um iniciante competente, não um alvo.

- Escolha o alvo mais próximo e a caçapa mais aberta; não tente corte fino.
- Potência média; não use efeito.
- Erro de mira de ±3° e 35% de chance de escolher uma opção pior de propósito.
- Não faça segurança elaborada: bata legal, devagar, sem encaçapar a branca.
- Evite a 8 até o grupo estar limpo.
- Aprenda o básico: a bola fantasma, a linha alvo→caçapa, a não encaçapar a branca.

**Não faça:** tabelas de duas ou três tabelas, efeito lateral, tacadas de posição, snooker.

## 4. Médio

**Objetivo:** jogador de bar forte. Encaçapa o que é fácil, erra o que é difícil.

- Pot primeiro: escolha a caçapa com margem e o corte mais reto.
- Use a bola fantasma para a mira; potência para a bola chegar com folga à caçapa.
- Planeje **uma** tacada à frente: a branca deve ficar com ângulo para a próxima bola do grupo.
- Se não houver pot real, faça uma segurança simples: bata legal, deixe a branca longe e a bola-alvo perto do trilho.
- Erro de mira de ±0,8° e 12% de chance de escolher uma alternativa pior.
- Não tente efeito forte; use no máximo um draw/follow suave para a posição.
- A 8: só depois do grupo limpo, com a caçapa escolhida e sem scratch.

**Não faça:** tacadas de três tabelas, combinações, snooker fino, posição de duas tacadas.

## 5. Difícil

**Objetivo:** jogador de clube. Tem que **acertar mais** e não dar presentes.

- Mira exata (0° de ruído), potência calibrada e **refinamento por simulação** ao redor do melhor candidato.
- Escolha a caçapa pela **margem**: boca menos o diâmetro da bola; evite cortes acima de ~70°.
- Leia o **corte e o throw**: a bola-alvo não sai exatamente na linha do ghost; o refinamento corrige.
- Potência: a mínima que chega com folga; bola rápida perde precisão e posição.
- Posição de **duas tacadas**: a branca deve terminar com ângulo para a próxima, longe do trilho e sem cluster.
- Segurança quando não há pot: esconda a branca atrás de uma bola, deixe a bola-alvo longe da caçapa, evite falta.
- Quebra: bata no rack com potência alta e centro da branca; abra sem encaçapar a branca.
- 8-ball: trate como uma bola de ouro; escolha a caçapa mais aberta, potência controlada, sem scratch.
- Nunca escolha uma tacada que encaçape a branca, que deixe a 8 na boca para o adversário ou que toque a bola errada primeiro.

**Não faça:** “matar” a bola sem need, usar efeito sem motivo, tentar combinação de baixa probabilidade quando há um pot simples.

## 6. Geometria da tacada

- **Ghost ball:** o centro da branca no contato fica a `2R` do centro da bola-alvo, na direção oposta à caçapa.
- **Corte:** o ângulo entre a direção branca→ghost e a direção alvo→caçapa. Quanto maior o corte, menor a margem e mais o throw importa.
- **Boca:** canto 116 mm, central 127 mm; a folga frontal é a boca menos 57 mm (bola). A linha da boca é a captura; o furo está 25 mm atrás.
- **Caçapa:** prefira a que dá a maior margem e o menor corte, não a mais próxima.
- **Trilho:** a bola pode bater na tabela antes da caçapa; a IA deve considerar o quique K-66.
- **Branca:** evite a linha da caçapa; se a branca passar perto da boca depois do contato, é scratch.

## 7. Potência e efeito

- A força é progressiva: 3% ≈ 20 cm; 10% ≈ 0,86 m; 25% ≈ 2,3 m; 50% ≈ 4,5 m; 100% ≈ 6,2 m/s.
- Para encaçapar, use a menor potência que chega com folga; para a posição, ajuste fino.
- **Draw/follow** mudam a saída da branca; use só quando a posição seguinte exige.
- **Side** altera o quique na tabela; use para corrigir a posição depois da tabela, não para “salvar” mira.
- Em tacadas curtas, o efeito é mais forte; em longas, o atrito come o efeito.

## 8. Segurança

Uma boa segurança:

1. é legal (toca primeiro o grupo certo);
2. não encaçapa a branca;
3. deixa a branca longe da bola-alvo e/ou atrás de outra bola;
4. deixa a bola-alvo longe da caçapa;
5. não deixa a 8 na boca.

Evite “segurança” que apenas empurra a bola para o meio da mesa. O adversário precisa de uma tacada difícil, não de uma bola fácil.

## 9. Quebra

- Bata no centro da branca, potência alta (70–90%), no rack.
- O objetivo é abrir as bolas sem encaçapar a branca e sem deixar a 8 na boca.
- Depois da quebra, escolha o grupo com a melhor distribuição; a IA não deve tentar a 8 cedo.

## 10. 8-ball

- Só depois de limpar o grupo.
- Escolha a caçapa mais aberta; potência controlada.
- Não encaçape a branca; não deixe a 8 na boca se errar.
- Se a 8 estiver bloqueada, jogue segurança e espere a oportunidade.

## 11. Treino offline

O pacote `ai_training.json` tem cenários com:

```json
{
  "id": "straight_center",
  "cue": [-0.6, 0.1],
  "target": 9,
  "targetPos": [0.55, 0.1],
  "obstacles": [[8, 0.0, 0.1]],
  "expected": "pot"
}
```

- `expected: "pot"` — a IA deve encaçapar o alvo.
- `expected: "legal"` — a IA deve tocar o alvo/grupo e não encaçapar a branca; o alvo pode ficar na mesa (segurança).
- `expected: "eight"` — a IA deve encaçapar a 8 e vencer.
- `obstacles` são `[número, x, z]`; o teste desativa todas as outras bolas.

Para rodar:

```bash
./gradlew :app:testDebugUnitTest --tests 'com.ldmaster.sinuca.game.AiTrainingTest'
./gradlew :app:testDebugUnitTest --tests 'com.ldmaster.sinuca.game.AiStrengthTest'
```

O `AiTrainingTest` imprime a taxa de acerto e a taxa de tacadas legais. O `AiStrengthTest` é o benchmark rápido de 10 layouts; o alvo do Difícil é **≥ 8/10** (hoje 9/10).

## 12. Métricas

- **Pot rate** — alvos encaçapados / cenários de pot.
- **Legal rate** — tacadas legais e sem scratch / todos os cenários.
- **Scratch rate** — branca na caçapa / todos.
- **Position** — a branca termina com ângulo para a próxima? (avaliar à mão nos cenários)
- **Safety** — o adversário fica sem pot fácil? (avaliar à mão)
- **Tempo de decisão** — o Difícil não pode travar a mesa; o alvo é < 1 s no celular.

## 13. Tuning

- O Difícil usa **36 candidatos** e refinamento de **5 ângulos × 3 potências** nos 4 melhores.
- O Médio usa 14 candidatos, ±0,8° e 12% de erro.
- O Fácil usa 5 candidatos, ±3° e 35% de erro.
- Para deixar o Difícil mais forte: aumente `maxCandidates`, reduza o `aimNoise` (já 0) e aumente o refinamento. Para deixar mais humano: adicione um pequeno erro de mira (±0,2°) e uma chance de segurança conservadora.
- Sempre rode o `AiTrainingTest` e o `AiStrengthTest` depois de mexer nos candidatos, potência ou score.

## 14. Drills para treinar

1. **Reta curta** — branca, alvo e caçapa alinhados; potência mínima.
2. **Corte 30°** — alvo deslocado; ghost ball; conferir a saída.
3. **Corte 60°** — margem pequena; o throw aparece; refinar.
4. **Longa** — 2 m; potência e mira.
5. **Lateral** — caçapa central; shelf de 25 mm.
6. **Perto do trilho** — a branca não pode encostar; usar o quique.
7. **Bloqueada** — segurança legal, sem scratch.
8. **Cluster** — não explodir o cluster sem need.
9. **8** — caçapa aberta, sem scratch.
10. **Quebra** — abrir sem branca.
11. **Branca perto da caçapa** — evitar scratch.
12. **Alvo na boca** — não deixar a 8 fácil.

## 15. Arquivos

- `docs/ai-playbook.md` — este documento.
- `app/src/main/assets/ai/playbook.md` — cópia dentro do APK.
- `app/src/test/resources/ai_training.json` — cenários do teste.
- `app/src/main/assets/ai/training.json` — cenários para inspeção/expansão.
- `AiTrainingTest.kt` — mede pot/legal/scratch.
- `AiStrengthTest.kt` — benchmark rápido 10 layouts.

A IA é **offline e determinística** no planejamento; o `Random` só entra no ruído/erro dos níveis mais baixos. “Treinar” aqui significa **medir e ajustar heurísticas com cenários**, não subir um modelo neural.
