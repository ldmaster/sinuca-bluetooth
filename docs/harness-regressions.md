# Retrospectiva do harness — por que as regressões passaram

Investigação feita depois das versões 1.11.0/1.11.1. Cobre o harness do Blender
(`/Users/lucasdavi/IA/blender-forge`), a skill (`game-forge`) e o
(`/Users/lucasdavi/IA/harnesskit`).

## 1. O que aconteceu

| # | Regressão | Como foi pega |
|---|---|---|
| R1 | Exportar do `.blend` pré-sculpt apagou `SM_PocketWells`, `SM_PocketRings` e os jaw caps | por inspeção manual do GLB |
| R2 | Os 5 estilos de textura colapsaram (0,25% de diferença) | pelo usuário, olhando a tela |
| R3 | Quinas quadradas / bevel possivelmente distorcido por escala não uniforme | pelo usuário |
| R4 | Composite preto por `glUniform1i` com enum em vez de índice | por screenshot |
| R5 | 20 miniaturas do menu desatualizadas em relação ao renderer | pelo revisor do plano |
| R6 | Uniforme morto (`uDetailStrength`) sem aviso | por leitura do shader |

## 2. O achado principal: o gate existia e estava vermelho

`forge apply` **avalia** o resultado e **retorna 1** quando o rubric falha
(`orchestrator.py:330`, `cli.py:282`). Os quatro exports desta sessão falharam:

```
run_..._45c743 (table_a) passed=False
  FAIL material_budget  materials=9
  FAIL triangle_budget  triangles=47884
  FAIL render_artifact  render=missing
```

E ninguém viu, porque o comando era:

```bash
forge apply ... | grep -oE '/Users[^"]*table_a.glb'
```

O status do pipeline é o do `grep` (0), não o do `forge`. `set -e` não protege
contra isso. **O guardrail foi desarmado pela forma de invocação, não por falta
de guardrail.**

Consequência secundária: um gate permanentemente vermelho vira ruído. O projeto
não tem `specs/rubric.toml`, então o forge cai no profile `prop`
(`max_triangles = 20000`, `max_materials = 8`, `require_render = true`). Uma mesa
de 48k triângulos e 9 materiais nunca vai passar nesse profile — o alarme estava
sempre ligado, então foi ignorado.

## 3. Causas-raiz

1. **Invocação que mascara o status.** `| grep`, `| head`, `|| true` em comandos
   que gateiam artefatos.
2. **Rubric genérico, não do projeto.** Nenhum `specs/rubric.toml` no repo; o
   perfil de prop não descreve uma mesa.
3. **Sem proveniência de estágio.** `--source` aceita qualquer `.blend`. Nada
   registra se aquele arquivo já passou pelo `sculpt_apron.py`. Exportar do
   arquivo errado é indetectável — a avaliação conta *meshes*, mas o rubric não
   exigia `SM_PocketWells`/`SM_PocketRings`/`SM_PocketJaws`.
4. **A avaliação só olha a cena do Blender, nunca o GLB.** Não há checagem de
   nós, materiais, atributos, índices, normais ou escala no artefato final. A
   perda dos copos só apareceu porque eu abri o GLB com um script próprio.
5. **Nenhuma regressão visual.** `eval.py` decide geometria e contagem; shader,
   uniforms, texturas e previews são invisíveis. R2, R4, R5 e R6 pertencem a
   essa categoria.
6. **Nenhum contrato entre asset e renderer.** `TableSurface` classifica por
   `(tema, material)`, mas nada garante que os 9 materiais de cada tema existam
   nem que a classe seja a esperada.

## 4. O que o harness já faz bem (não mexer)

- Operações tipadas + `guard.py` (path confinement, AST allowlist) + snapshots.
- `eval.py` determinístico, com `passed` e exit code corretos.
- Sistema de profiles/rubric, com lookup por workspace (`specs/rubric.toml`) —
  ou seja, o encaixe para o rubric do projeto **já existe**.
- `harnesskit.vision.visual_diff` (razão de pixels alterados, com tolerância) e
  `CheckSpec`/`CriterionMode` (programmatic/ground_truth/tool_use/judge).

## 5. Propostas priorizadas

### P0 — impede a repetição de R1 e do gate ignorado

**P0.1 Rubric do projeto** — `sinuca-bluetooth/specs/rubric.toml`:

```toml
name = "sinuca-table"
pass_threshold = 1.0
max_triangles = 90000
max_vertices = 60000
min_triangles = 30000
max_materials = 16
required_objects = ["SM_Apron", "SM_Rails", "SM_Legs", "SM_PocketWells",
                    "SM_PocketRings", "SM_PocketJaws", "SM_Cloth", "SM_Slate"]
require_render = false      # o plano de export não renderiza
require_glb = true
max_non_manifold_edges = 0
max_dimension = 4.0
```

**P0.2 Pipeline como código** — `scripts/build_tables.sh` com
`set -euo pipefail`, um `forge apply` por estágio **sem pipe**, checando
`passed` no `forge.json`, e gravando `var/rebuild/manifest.json` com
`source_sha256`, `stage`, `blender_version`, `script_sha256`, `artifact_sha256`,
`triangles`, `materials`. Se qualquer estágio falhar, nada é copiado para
`assets/models/`.

**P0.3 Auditoria do GLB** — `scripts/glb_audit.py` (ou `forge verify glb` no
harness) rodando depois do export e no CI:

- nós obrigatórios presentes (`SM_PocketWells`, `SM_PocketRings`, `SM_PocketJaws`…);
- materiais por nome e contagem;
- `triangles` na faixa, `verts/tri < 1.0` (proxy de shading suave);
- bbox dentro de ±3 mm do baseline;
- índices 32-bit quando `vertices > 65535`;
- nenhum nó com escala não uniforme entre os que receberam bevel.

**P0.4 Marcador de estágio** — `sculpt_apron.py` e `smooth_table.py` gravam
`obj.data["forge_stage"]` e `bpy.context.scene["forge_source_sha"]`. O export
recusa `--source` cujo estágio não seja o esperado. No harness: `--require-stage`
no `forge apply`.

### P1 — impede a repetição de R2, R3, R4 e R5

**P1.1 Regressão visual com baseline.** Commitar 4 temas × 2 câmeras em
`var/visual-baseline/` e comparar com `harnesskit.vision.visual_diff`
(`changed_ratio <= 0.02`). Mais importante: um teste de **separação entre
estilos** sobre as 20 previews — todo par precisa diferir mais que 1,5% na faixa
do trilho. Esse teste pega R2 hoje, sem modelo, sem GPU.

**P1.2 Frescor das previews.** `previews/.stamp` com hash de `Shaders.kt` +
GLB. CI falha se o renderer mudou e as miniaturas não foram regeradas (R5).

**P1.3 Contrato de uniforms.** Um objeto Kotlin com os nomes esperados; em
debug, depois do link, falhar se `glGetUniformLocation` devolver `-1`, e checar
`glGetError()` ao fim de cada passe. Pega R4 e R6 antes do screenshot.

**P1.4 Métricas no harness.** Adicionar aos stats do runner
(`scripts/runner.py:633`): `smooth_polygons`, `scale`, `applied_modifiers`,
`materials[]` por objeto e `color_attributes`. No rubric: `min_triangles`,
`max_verts_per_triangle`, `min_smooth_fraction`, `required_material_names`.
Em `ops.ExportOp`: `apply_modifiers`, `export_vertex_colors`, `yup` — hoje as
opções do export são implícitas.

**P1.5 Guardas no `smooth_table.py`** (R3): antes do bevel, exigir escala ≈ 1
(ou aplicar), `width < 0.25 × menor dimensão` do objeto; depois, exigir que os
triângulos cresçam dentro da faixa e que `non_manifold` não aumente. Sair com
código ≠ 0 em vez de só imprimir.

### P2 — sistêmico

**P2.1 harnesskit**: `vision.py` ganhar SSIM/ΔE além da razão de pixels; novos
checks `GeometryCheck`, `GlbCheck`, `VisualSeparationCheck`; `CheckKind.VISUAL`;
baseline de métricas com tolerância.

**P2.2 Skill `game-forge`**: nova seção "assets em projeto existente" — a regra
1 ("use forge, não bpy") **não vale** quando o repo tem um script `bpy` como
fonte de verdade (é o caso do `sculpt_apron.py`); nunca exportar de um estágio
não produzido; nunca canalizar `forge` por pipe; mudança visual exige
antes/depois medido; regerar previews quando o renderer muda.

**P2.3 CI**: job que roda `glb_audit.py` + o teste de separação de estilos em
todo push que toque `assets/models`, `render/` ou `previews/`.

## 7. Status (implementado)

| Item | Onde |
|---|---|
| Rubric do projeto | `specs/rubric.toml` (90k/30k triângulos, 8 materiais, 11 objetos obrigatórios, `min_smooth_fraction`, `max_vertices_per_triangle`) |
| Export sem pipe e com gate | `scripts/forge_export.py` (lê `passed`, imprime só o artefato no stdout) |
| Auditoria do GLB | `scripts/glb_audit.py` (nós, materiais, tris, verts/tri, índices, atributos, bbox) |
| Marcador de estágio | `sculpt_apron.py` e `smooth_table.py` gravam `<blend>.stage.json`; `scripts/pipeline.py check-stage` |
| Pipeline completo | `scripts/build_tables.sh` (`set -euo pipefail`, para no primeiro erro) |
| Manifesto de proveniência | `scripts/pipeline.py manifest` → `var/rebuild/manifest.json` |
| Frescor das previews | `scripts/visual_check.py --check-stamp` (digest estrutural do GLB, não bytes) |
| Separação entre estilos | `scripts/visual_check.py --styles` (piso 1%, pior par medido 1,49%) |
| Baseline visual | `scripts/visual_check.py --baseline` (changed-ratio + SSIM) |
| Contrato de uniforms | `GameRenderer.uniform()` + `PostFx.uniform()` logam nomes ausentes |
| Guardas de geometria | `smooth_table.py`: bevel < 25% do eixo mais fino, escala aplicada, triângulos não caem e non-manifold não cresce; falha com exit 1 sem salvar |
| CI | `.github/workflows/android.yml` roda audit, stamp e separação |
| Stats/rubric do harness | `runner.py` (`smooth_fraction`, `vertices_per_triangle`, `applied_scale`, `color_attributes`), `eval.py` (`triangle_floor`, `vertex_triangle_ratio`, `smooth_shading`, `required_materials_by_name`, `applied_scale`) |
| Export explícito | `ops.ExportOp.apply_modifiers`, `vertex_colors`, `yup` |
| harnesskit | `vision.ssim`, `vision.pairwise_separation`, `SeparationReport`, `CheckKind.VISUAL` |
| Skill | `game-forge`: seção "Existing engine project" + "Quality floor for visual changes" |

## 8. Piso de qualidade (checklist para qualquer mudança de modelagem)

1. `forge apply` sem pipe; `passed == true` no `forge.json`.
2. `glb_audit.py` verde (nós, materiais, tris, verts/tri, bbox, índices).
3. Escala aplicada; bevel dentro do limite de espessura; shading suave.
4. Preview/baseline regerado quando o shader ou o GLB mudou.
5. Separação entre estilos ≥ 1,5% no trilho quando o estilo for tocado.
6. `testDebugUnitTest`, `lintDebug` e `assembleRelease` verdes.
