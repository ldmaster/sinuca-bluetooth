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
| Espessura do pano | 4 mm | feltro sobre o slate |
| Slate | 46 mm | laje escura sob o pano |
| Diâmetro interno do copo (canto / central) | 136 / 140 mm | furo visível da caçapa |
| Furo no trilho (canto / central) | 144 / 148 mm | recorte do trilho; o copo o cobre |
| Parede do copo | 7 mm | revestimento do furo |
| Profundidade do poço | 165 mm | drop pocket |
| Faceta de borracha | 22 mm | ponta escura da almofada |

## Geométrico

- A caçapa é um **círculo completo atrás da linha da boca**, recuado pelo raio
  interno mais um **shelf de 25 mm**. No canto, a linha da boca fica 58 mm
  dentro do canto da área de jogo (metade da boca de 116 mm); o centro fica
  35 mm além do canto na diagonal, ou 24,75 mm em cada eixo. Medir esse recuo
  a partir do canto em vez da linha da boca deixava 83 mm de madeira entre a
  boca e o copo.
- O pano e o slate são recortados **apenas nos seis círculos das caçapas**. O
  resto do pano fica liso e inteiro; o shelf de 25 mm entre a linha da boca e o
  aro do copo continua coberto de pano.
- O copo é um tubo fechado embaixo. A metade do aro voltada para a mesa é
  rebaixada para logo acima do pano, para a bola entrar; a metade externa sobe
  até a altura do trilho e recebe um aro metálico fino. O copo é 3 mm maior que
  o furo do trilho e 2 mm maior que o recorte do apron, de modo que as bordas
  ficam sempre sobrepostas e nenhuma fresta aparece.
- As pontas das almofadas são recuadas para abrir as bocas (82,0 mm em cada
  direção no canto, 63,5 mm de cada lado na central) e recebem facetas escuras
  de 22 mm.
- O pano (4 mm) e o slate (46 mm) avançam até a boca e são recortados só no
  círculo do copo. O apron é uma moldura fechada nos quatro lados, com o topo
  3 mm dentro do trilho e recortes apenas nas seis caçapas; os painéis curtos
  existem e recebem o recorte circular da caçapa de canto.

## Física correspondente

`Table.kt` expõe as mesmas grandezas:

- A captura acontece quando o centro da bola cruza a linha da boca: a corda
  entre as pontas no canto (`u + v ≤ CORNER_TIP`) ou a linha da almofada na
  central (`|x| ≤ SIDE_TIP` e a bola além da linha). A animação de queda leva
  a bola até o centro do círculo visível.
- A captura fica na boca (e não no centro do círculo) para que uma bola que
  rola junto à almofada também caia. Uma bola que passa fora da boca bate na
  mandíbula (cápsula da almofada) e continua em jogo.
- A folga para uma bola entrar de frente na caçapa central é a boca menos o
  diâmetro da bola (127 − 57 ≈ 70 mm, ou ±35 mm de centro), que é o que o
  laboratório mede.

## Fontes

- [WPA — Pool Table Dimensions & Regulation Size](https://www.wpa-pool.com/pool-table-dimensions-regulation-size-explained/)
- [WPA pocket specifications (resumo)](https://www.generationbilliards.com/generation-billiards-journal/what-wpa-pocket-specifications-actually-certify)
- [Dimensions.com — Billiards pool table pockets](https://www.dimensions.com/element/billiards-pool-table-pockets)
