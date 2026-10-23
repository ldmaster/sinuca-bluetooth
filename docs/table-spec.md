# Especificação da mesa

A mesa é gerada por `assets-plans/generate_table.py` (operações tipadas do
`blender-forge`) a partir das medidas reais de uma mesa de 9 pés. O jogo usa as
mesmas medidas em `game/Table.kt`; qualquer mudança de geometria precisa ser
feita nos dois lugares.

## Medidas de referência

| Item | Modelo | Referência |
|---|---:|---|
| Área de jogo | 2,54 × 1,27 m | 100 × 50 in (9 ft, WPA) |
| Diâmetro da bola | 57,15 mm | bola de sinuca americana |
| Boca da caçapa de canto | 116 mm | 4,5–4,625 in (WPA) |
| Boca da caçapa central | 127 mm | 5–5,125 in (WPA) |
| Altura do nariz da almofada | 36 mm | 63,5% do diâmetro da bola |
| Largura do trilho | 195 mm | mesa de torneio |
| Dimensão externa | 2,93 × 1,66 m | 9 ft com trilhos |
| Topo do pano | 750 mm | altura de jogo 74–79 cm |
| Topo do trilho | 800 mm | 1,5 in acima do pano |
| Furo do pano (canto / central) | 138 / 142 mm | abertura do slate |
| Furo do trilho (canto / central) | 160 / 164 mm | recorte na madeira |
| Aro do pocket, diâmetro externo | 188 / 192 mm | casting metálico |
| Profundidade do poço | 165 mm | drop pocket |

## Geométrico

- A caçapa de canto é uma circunferência centrada no vértice da área de jogo,
  com o trilho de madeira contornando-a por fora. Não existe recorte aberto na
  moldura: o buraco é usinado dentro do trilho.
- A boca é definida pelo recuo das pontas das almofadas: 82,0 mm em cada
  direção no canto (`116/√2`) e 63,5 mm de cada lado da caçapa central.
- As almofadas têm 48 mm de corpo atrás do nariz, 36 mm de altura e 6 mm de
  arredondamento. O nariz fica exatamente na borda da área de jogo.
- O copo do pocket reveste o furo do trilho de baixo a cima (parede de 12 mm),
  de modo que a parede visível é sempre o material escuro do copo, nunca a
  madeira. O aro metálico fica 0,5 mm acima do topo do trilho, sem faces
  coplanares com a madeira.
- O furo do pano é 1 mm maior que a parede interna do copo; o copo cobre a borda
  do pano e elimina o z-fighting. A física usa o raio interno do copo (68 mm no
  canto, 70 mm na central), que é o que o jogador vê.

## Física correspondente

`Table.kt` expõe as mesmas grandezas:

- `POCKETS` — centros em `(±1,27, ±0,635)` e `(0, ±0,635)`, raios de captura
  68 mm e 70 mm.
- `CUSHIONS` — seis segmentos de reta no nariz das almofadas; a colisão é por
  cápsula, então as pontas funcionam como mandíbulas arredondadas.
- A captura só acontece quando o centro da bola entra no raio do copo; uma bola
  que passa de raspão bate na mandíbula/saia e continua em jogo.

## Fontes

- [WPA — Pool Table Dimensions & Regulation Size](https://www.wpa-pool.com/pool-table-dimensions-regulation-size-explained/)
- [WPA pocket specifications (resumo)](https://www.generationbilliards.com/generation-billiards-journal/what-wpa-pocket-specifications-actually-certify)
- [Dimensions.com — Billiards pool table pockets](https://www.dimensions.com/element/billiards-pool-table-pockets)
