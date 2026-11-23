# Vision Paint Macro

App Android que usa o compartilhamento de tela para procurar imagens que você enviou.
Quando uma delas aparece, ele pinta o local na cor escolhida e, se ligado, arrasta o dedo
em linha reta do seu ponto de partida até o alvo (a rota aparece na tela).
No **modo mira** (versão 1.2), em vez disso ele leva a mira do jogo até o centro da imagem
e a mantém fixa ali.
Na **versão 1.5** ele também reconhece **modelos 3D** (veja abaixo).

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

## Versão 1.5.0: modelos 3D como referência

Novo cartão **3. Modelos 3D**. Em vez de (ou junto com) imagens, você envia um modelo 3D, escolhe
**em que parte dele a mira deve ficar** (cabeça, peito...) e o app o procura na tela em qualquer
ângulo, tamanho e inclinação.

**Como usar**
1. Cartão 3 > **Adicionar modelos 3D**. Formatos: `.glb`, `.gltf` (com tudo dentro do arquivo),
   `.obj` e `.stl`, até 120 MB. Pode adicionar quantos quiser, inclusive parecidos.
   Para `.gltf` com arquivos separados ou `.obj` com `.mtl`/texturas, converta para `.glb`
   (as cores não entram na busca, mas arquivos externos não são lidos).
2. Abre a tela do modelo: **arraste para girar** e **toque no modelo** para pôr o ponto de mira
   (ele entra um pouco no corpo, não fica na "pele"). Atalhos: **Cabeça, Peito, Centro, Pés**.
   O **raio** é a área que conta como "na mira" (o anel; tracejado = o ponto está atrás de outra
   parte nesta vista). Se o modelo aparece deitado, troque o **eixo "para cima"** (`.stl` e Blender
   costumam usar Z; a maioria dos `.glb` usa Y). **Salvar** também prepara as vistas.
3. **Arrastar até ele** (por modelo) funciona como nas imagens e também vale no modo mira, que
   passa a mirar no **ponto escolhido** em vez do centro do retângulo. No painel flutuante, o
   seletor "3 Imagem" lista imagens e modelos.
4. Ajustes do cartão: **Sensibilidade dos modelos** (nota para achar), **nota para seguir**,
   **menor/maior tamanho na tela** (em % do lado menor), **busca precisa** (27 vistas na busca grossa em vez de 9)
   e **aceitar inclinação de lado** (±20°). Tamanho, precisa e inclinação valem ao iniciar.

**Como funciona** (tudo sem rede neural e sem GPU)
- O modelo é desenhado por software (`Rasterizer.kt`) em 18 giros x 3 inclinações de câmera e
  vira um conjunto de pontos de contorno com a direção da borda (`ViewBank.kt`). O ponto de mira
  fica guardado dentro do modelo e é projetado em cada vista.
- Na tela, `ModelMatcher.kt` compara a **direção das bordas** (não as cores) em uma pirâmide de
  tamanhos, com correlação (`CvFieldOps.kt`, OpenCV). Os melhores candidatos são refinados
  (vista vizinha, tamanho, inclinação) e depois **verificados por cobertura**: que fração do
  contorno do modelo tem, na tela, uma borda forte na direção certa. A **nota** mostrada é essa
  cobertura descontada do que um contorno deslocado também "cobriria" por acaso.
- Entre uma varredura e outra o alvo é seguido a partir da pose anterior (rápido), e o ponto de
  mira é suavizado junto com o rastreio.
- As vistas são guardadas em cache (`ModelStore.kt`), então só a primeira vez é lenta.

**Resultados medidos (e o que isso NÃO prova)**
- Testei só em cenas **sintéticas**: um boneco de caixas desenhado em fundos com retângulos,
  ângulos e tamanhos sorteados (30 cenas por grupo, `MatchMain.kt` no meu ambiente de testes).
  Com a sensibilidade padrão (50%): achou o modelo em 26 de 30 cenas (com 0, 3 e 6 objetos de
  distração), errou em 1 de 30 quadros **sem** modelo, e a mira ficou a ~1% do tamanho do modelo
  do ponto verdadeiro. Seguir: 30 de 30 com nota para seguir de 25% a 35%.
- Isso **não** foi testado em jogo real, com outros modelos, com efeitos, partículas, neblina ou
  alvo parcialmente tapado. Em fundos muito poluídos a nota cai e o app pode perder o alvo.
- Formas muito simétricas ou parecidas com o cenário (caixas contra caixas) são o caso mais
  difícil. Modelos finos/longos e com contorno bem definido são mais fáceis.
- **Tempo de varredura no celular não foi medido.** Estimo alguns segundos por varredura completa
  com 1 modelo e quadros 540x1170 (mais lento na busca precisa e proporcional ao número de
  modelos). Faixa de tamanho estreita e poucos modelos aceleram. Entre varreduras o rastreio é
  rápido (dezenas de ms no teste de laboratório).
- As partes do Android (tela do modelo, cartão, serviço) e a ligação com o OpenCV **não foram
  compiladas aqui**: conferi tipos e nomes com um compilador de teste, mas o primeiro build do
  GitHub Actions é a verificação de verdade. Se der erro, mande o texto do erro.

**Ajuste fino**
- Acha alvos falsos: suba a **Sensibilidade dos modelos**. Não acha o modelo: baixe (a escala é
  outra que a das imagens; modelos de verdade ficam em geral entre 50% e 80%).
- Mira "pulando": use um **raio** maior ou menos vistas (modo rápido).
- Para depurar o seu jogo HTML, comece com o modelo grande na tela, fundo simples e um modelo só.

## Versão 1.4.0: rastreio que aguenta borrão e tremor

Tudo novo fica no cartão **8. Rastreio e desfoque** (e na **Nota para seguir**, no cartão 4).

- **Histerese e inércia.** Achar um alvo exige a **Sensibilidade** (alta, ex.: 80%). Para continuar
  seguindo, basta a **Nota para seguir** (ex.: 55%), porque já se sabe onde ele estava; perto de
  onde ele deveria estar vale a nota baixa, e quanto mais longe, mais perto da nota alta (assim
  um ruído parecido com o alvo não vira um "alvo fantasma"). Se o alvo some, o app segue a última
  posição por **Segurar o alvo perdido** (padrão 400 ms) antes de desistir. A pintura fica mais
  fraca enquanto a posição é só uma previsão.
- **Filtro de Kalman.** Suaviza o tremor da posição e prevê pela velocidade (`Kalman.kt`,
  `TargetTracker.kt`). Em simulação reduz o tremor em 10% a 30%; o ganho maior é a previsão
  durante a inércia. **Quanto suavizar** troca tremor por atraso.
- **Compensar o tremor da tela inteira** (`ShakeEstimator.kt`). Mede, com `phaseCorrelate`, quanto
  a tela toda andou entre dois quadros e corrige a previsão dos alvos mesmo quando a detecção
  falha. Ignora o miolo da mira (a mira fixa do jogo estragaria a medida) e os próprios alvos, e
  devolve "não sei" quando a confiança é baixa. No modo mira, a zona de travamento alarga
  enquanto a tela treme, para a mira não "caçar" um alvo que só tremeu.
- **Desfoque.** *Versões borradas e de outro tamanho* guardam, para cada imagem, variantes que o
  rastreio testa (só na região pequena, então custa pouco). *Desfoque leve antes de comparar*
  borra o quadro e as imagens. Os dois valem ao iniciar. Dica sem código: adicione 2 ou 3 prints
  do mesmo alvo desfocado ou tremendo.
- **Achar por cor** (por imagem, cartão 2). Usa a cor marcante da imagem para achar candidatos e a
  imagem só confirma (nota entre a de achar e a de seguir): a varredura completa fica bem mais
  rápida. No rastreio, a cor segura o alvo quando a imagem não combina por causa do borrão, mas
  só por até 1,5 s. Imagens cinzas, sem cor marcante, ignoram a opção (o app avisa ao iniciar).
- Organização: o que acontece a cada quadro agora está em `TrackingPipeline.kt`, sem Android.

**Não incluído (de propósito):**
- *Rastreadores prontos do OpenCV*: o `org.opencv:opencv:4.9.0` traz `TrackerMIL` (e GOTURN,
  DaSiamRPN, Nano e Vit, que precisam de modelos), mas **não** traz KCF nem CSRT (ficam no
  pacote contrib). O MIL não trata mudança de tamanho e costuma derivar, então o rastreio por
  imagem com previsão faz o mesmo papel com mais controle.
- *Rede neural (YOLO nano / MobileNet-SSD)*: precisa de um modelo treinado, que não dá para gerar
  aqui, e deixa o APK maior e gasta mais bateria. É o próximo passo se isto não bastar.

## Versão 1.3.2: pintura acompanha o alvo durante o arrasto

- **Corrigido:** durante o arrasto automático a pintura vermelha ficava parada no lugar antigo do
  alvo até o arrasto terminar. A causa era a detecção lenta: a cada quadro o app procurava as
  imagens na tela inteira, e isso demorava mais que o próprio arrasto.
- Agora, depois de achar um alvo, o app o **rastreia**: procura de novo só na região ao redor
  de onde ele estava (`Detector.track`, `TrackMath.kt`), o que é bem mais rápido. Se o rastreio
  perde o alvo, ele volta na hora para a busca na tela inteira.
- A busca na tela inteira continua rodando de tempos em tempos (no mínimo a cada 0,5 s e, se for
  lenta, a cada 3x o tempo que ela leva) para achar alvos novos. Um alvo novo pode demorar um
  pouco mais para ser pintado enquanto outro está sendo rastreado.
- Se ainda ficar lento: em **Qualidade da captura**, diminua o valor (vale ao iniciar).

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
- **Nota para seguir**: quanto o alvo já achado precisa combinar para continuar sendo seguido
  (menor que a Sensibilidade). Se o alvo some quando borra, baixe; se o app segue coisa errada, suba.
- **Qualidade da captura**: menor = mais rápido, maior = mais preciso (vale ao iniciar).
- **Ponto de partida**: onde o dedo começa o arrasto (em % da tela).
- **Velocidade / pausa**: controlam a duração do arrasto e o intervalo entre eles.
- Imagens novas e a qualidade da captura só valem ao iniciar de novo.
- O ponto de partida agora é guardado com mais precisão (décimos de %) para poder ser marcado
  direto na tela; os valores salvos pelas versões anteriores são aproveitados.

## Limitações

- Imagens lisas, sem detalhe (um quadrado de uma cor só), não são reconhecidas.
- Modelos 3D: a busca é por contorno (veja a versão 1.5.0): não usa cores nem texturas, perde
  força em fundos poluídos ou com o alvo tapado, e a varredura completa é pesada no celular.
- O reconhecimento é por comparação de imagem: tamanho muito diferente do original
  (zoom, outra resolução) reduz a precisão.
- O arrasto é em linha reta e o app escolhe o alvo mais perto do ponto de partida.
- A compensação do tremor mede a tela inteira: elementos parados no meio da tela (fora a mira
  configurada) puxam a medida para zero. Com pouca textura, ela se abstém em vez de chutar.
- Alguns apps e jogos bloqueiam captura de tela ou gestos de acessibilidade.
  Use apenas onde isso for permitido.
