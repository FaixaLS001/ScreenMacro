# Vision Paint Macro

App Android que usa o compartilhamento de tela para procurar imagens que você enviou.
Quando uma delas aparece, ele pinta o local na cor escolhida e, se ligado, arrasta o dedo
em linha reta do seu ponto de partida até o alvo (a rota aparece na tela).
No **modo mira** (versão 1.2), em vez disso ele leva a mira do jogo até o centro da imagem
e a mantém fixa ali.

## Como gerar o .apk (só com o celular)

1. Crie um repositório no GitHub e envie todo o conteúdo desta pasta (inclusive `.github`).
2. Abra a aba **Actions** > **Build APK** > **Run workflow**
   (ele também roda sozinho a cada envio).
3. Quando terminar (uns 5 a 8 minutos), abra a execução e baixe o arquivo
   **VisionPaintMacro-apk** (vem dentro de um .zip, extraia e instale o `app-debug.apk`).

Alternativa: abrir esta pasta no Android Studio e usar Build > Build APK.

## Primeiro uso

1. **Permitir sobreposição na tela**.
2. **Ativar serviço de acessibilidade** (só é preciso para o arrasto).
   Se o Android bloquear: Configurações > Apps > Vision Paint Macro > menu ⋮ >
   "Permitir configurações restritas", e tente de novo.
3. **Adicionar imagens**: recortes do que você quer reconhecer (ícone, personagem etc.).
   Faça o print na mesma tela/resolução em que vai usar.
4. Escolha a cor e ajuste os controles.
5. **INICIAR**, autorize o compartilhamento de tela e abra o app/jogo.
   Para parar, use o botão **Parar** da notificação ou o botão PARAR do app.

## Versão 1.3.1: correção do toque

- **Corrigido:** com o macro rodando (principalmente no modo mira) não dava para tocar nem
  arrastar a tela; só a bolinha e as notificações respondiam. O Android 12+ bloqueia o toque que
  passa por camadas de tela cheia de outro app quando a opacidade da janela delas passa de 0,8.
  As duas camadas (pintura e marcadores da mira) agora usam opacidade de janela menor
  (`TouchSafe` em `OverlayView.kt`) e o desenho é reforçado para continuar com a mesma aparência.
- Efeito colateral: a pintura não passa de uns 55% de opacidade e os marcadores ficam um pouco
  mais fracos em uso. Ao posicionar um marcador ele continua totalmente visível.
- Se o toque ainda não passar: use **Pausar** na bolinha para testar, e confira se o arrasto
  automático (modo mira) não está "tocando" sozinho por cima do seu dedo.

## Novidades da versão 1.3: visual novo

- **Tema claro e escuro** automático, seguindo o do celular (cores em `res/values` e `res/values-night`).
- **Tela principal redesenhada**: cabeçalho com o logo, seções em cartões numerados, sliders
  coloridos (vermelho, verde e azul na escolha de cor), chaves e botões no mesmo estilo, e uma
  **barra fixa embaixo** com o estado (Rodando/Parado) e o botão INICIAR/PARAR sempre à mão.
- **Novo ícone do app**: moldura de captura com uma mira no centro. É um ícone adaptativo
  (com versão monocromática para o Android 13+) e traz PNGs para o Android 7.
- **Bolinha flutuante** com a mira desenhada (e o símbolo de pausa quando pausada), painéis com
  bordas arredondadas e efeito de toque nos botões.
- **Notificação** com ícone próprio e cor do app.
- Nada mudou no funcionamento: mesmas permissões, ajustes e modo mira da versão 1.2.

## Novidades da versão 1.2: modo mira

Pensado para jogos de tiro em que o dedo arrasta numa área da tela para mover a mira
(a mira fica parada no centro e o cenário se move). O app mede a distância entre a mira e o
centro da imagem e arrasta o dedo, em passos curtos, até a mira ficar em cima dela.

**Painel de mira** (bolinha flutuante > Painel de mira). Ele abre deitado, ao lado da bolinha:

| Botão | O que faz |
|---|---|
| **1 Toque** | Toque ou arraste na tela para marcar **onde o dedo pressiona** para começar a arrastar. Termine em **OK**. |
| **2 Mira** | Marque **onde fica a mira do jogo**. Esse marcador é quase invisível quando o macro está rodando (fica verde quando a mira está fixa no alvo). Termine em **OK**. |
| **3 Imagem** | Escolha **em qual(is) imagem(ns)** a mira se fixa (toque nas miniaturas; verde = escolhida). É a mesma opção "Arrastar até ela" da lista de imagens. |
| **Travar** | Liga/desliga o modo mira (liga o arrasto automático e fecha o painel). |

Para voltar ao arrasto de antes (do ponto de partida até a imagem), desligue **Modo mira** na tela
principal; o botão Travar do painel só liga/desliga o dedo automático dentro do modo mira.

Como funciona:

- O alvo é a imagem escolhida mais perto da mira. Depois que a mira trava em uma imagem, o app
  continua nela mesmo que apareça outra mais perto.
- A cada arrasto o app mede de novo e corrige. Dentro da **zona de travamento** ele para e a mira
  fica fixa; se a imagem se mover e sair da zona, ele corrige de novo.
- Com o painel aberto (ou posicionando um marcador) o macro **não arrasta**, para o dedo
  automático não tocar no painel. Feche o painel para ele agir.
- O ponto de pressão deve ficar longe da bolinha e dos botões do jogo que você não quer tocar.

Ajustes (tela principal > 5. Modo mira):

- **Sensibilidade da mira**: quanto o dedo anda por pixel de distância até a imagem. Com
  **Aprender sozinho** ligado, o app mede como o jogo responde a cada arrasto e corrige esse
  valor; o número só precisa ser um palpite razoável.
- **Zona de travamento**, **Pausa entre correções** e **Arrasto mínimo** (jogos e navegadores que
  ignoram arrastos muito curtos: o dedo vai além e volta, terminando no mesmo lugar).
- **Inverter horizontal / vertical**: se a mira se afasta da imagem em vez de chegar nela, ligue
  a inversão do eixo (o app também avisa quando percebe isso).
- Para ver a rota da mira até a imagem, deixe "Mostrar a rota na tela" ligado; desligue para a
  tela ficar limpa.

## Novidades da versão 1.1

- **Bolinha flutuante** (verde = rodando, amarela = pausado): arraste para mover, toque para
  abrir o menu com Pausar/Retomar, Arrasto ligado/desligado e Parar.
- **Cor e regra por imagem**: cada imagem pode ter a sua cor (toque na cor) e a opção
  "Arrastar até ela". Sem cor própria, vale a cor padrão. Use ↑ e ↓ para ordenar a lista.
- **Qual alvo seguir** (seção Arrasto): mais perto do ponto de partida, maior nota ou ordem
  da lista.
- **Chave de assinatura fixa** (`signing/`): a partir daqui, uma versão nova atualiza a anterior
  por cima, sem perder imagens e ajustes. Só a primeira vez que você sair da versão 1.0
  precisa desinstalar a antiga.

## Ajustes

- **Sensibilidade**: se não reconhece, baixe; se pinta coisa errada, suba.
- **Qualidade da captura**: menor = mais rápido, maior = mais preciso (vale ao iniciar).
- **Ponto de partida**: onde o dedo começa o arrasto (em % da tela).
- **Velocidade / pausa**: controlam a duração do arrasto e o intervalo entre eles.
- Imagens novas e a qualidade da captura só valem ao iniciar de novo.
- O ponto de partida agora é guardado com mais precisão (décimos de %) para poder ser marcado
  direto na tela; os valores salvos pelas versões anteriores são aproveitados.

## Limitações

- Imagens lisas, sem detalhe (um quadrado de uma cor só), não são reconhecidas.
- O reconhecimento é por comparação de imagem: tamanho muito diferente do original
  (zoom, outra resolução) reduz a precisão.
- O arrasto é em linha reta e o app escolhe o alvo mais perto do ponto de partida.
- Alguns apps e jogos bloqueiam captura de tela ou gestos de acessibilidade.
  Use apenas onde isso for permitido.
