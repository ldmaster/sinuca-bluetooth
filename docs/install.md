# Instalar no celular sem o Android tratar como vírus

O aviso não é um antivírus dizendo que o app é malicioso. É o **Play Protect**
sinalizando um APK que não veio da Play Store e cujo desenvolvedor ainda não tem
reputação. Todo app instalado por fora da loja passa por isso, inclusive os
assinados corretamente.

## Caminho recomendado

1. Baixe o APK direto da página de releases no navegador do celular:
   `https://github.com/ldmaster/sinuca-bluetooth/releases/latest`
   Evite WhatsApp/Telegram: eles às vezes renomeiam ou bloqueiam `.apk`.
2. Ao abrir o arquivo, o Android pede **“Permitir instalar apps desconhecidos”**
   para o app que está abrindo o APK (Chrome, Arquivos…). Isso é por app e é
   pedido **uma vez** por origem.
3. Se aparecer **“App não verificado”** ou **“Play Protect não reconhece este
   app”**, toque em **Mais detalhes → Instalar assim mesmo**. É o esperado.
4. Em **Xiaomi / HyperOS / MIUI**, além do Play Protect existe a “Análise de
   segurança” da própria MIUI: toque em **Continuar/Instalar mesmo assim**. Se
   quiser parar de ver o aviso, desligue temporariamente
   `Segurança → Play Protect → Verificar apps` (ou o equivalente da MIUI).

## Por que às vezes pede confirmação várias vezes

- Cada app de origem (navegador, gerenciador de arquivos, GitHub) tem a sua
  própria permissão de “instalar apps desconhecidos”.
- O Play Protect verifica **cada APK** baixado, não só o primeiro.
- Se você desinstala antes de instalar de novo, o Android trata como instalação
  nova e repete as confirmações.

Instalar **por cima** da versão anterior (mesma assinatura) não pede nada além do
normal e preserva o placar e o aprendizado da IA.

## Sem nenhum aviso: adb

Com o celular conectado e depuração USB ligada:

```bash
./scripts/install_phone.sh                 # pega o APK mais recente do release
adb install -r sinuca-bluetooth-v1.12.0.apk
```

`adb install` não passa pela interface de instalação, então não há Play Protect
nem diálogo de origem desconhecida.

## Conferir que o APK é o oficial

A chave de assinatura é estável desde a primeira versão. O certificado esperado:

```
SHA-256: f9f09a4c086a837207cb31f04c83d7cc72e8f5124a881d9ccca639b16fb87be3
SHA-1:   c8c30da36773e6b61ce379f07641c92943e094a3
CN=Sinuca Bluetooth, O=ldmaster, C=BR
```

Verificação no computador:

```bash
apksigner verify --print-certs app-release.apk
```

Se o SHA-256 não bater, **não instale**: o arquivo não saiu deste repositório.

## O que realmente remove o aviso

- **Play Store (trilha de teste interno)**: o APK passa a ser “verificado” e o
  aviso desaparece para quem entra pelo convite. É a única solução definitiva.
- **Obtainium** (Android): instala e atualiza direto das releases do GitHub; o
  aviso do Play Protect continua na primeira instalação, mas as atualizações
  ficam automáticas.
- **F-Droid**: evita o Play Protect, mas exige manter um repositório próprio.

Nada dentro do app consegue desligar o aviso: ele é do sistema, baseado em
procedência, não no conteúdo do APK.
