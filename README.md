# Vision Paint Macro

App Android que usa o compartilhamento de tela para procurar imagens que você enviou.
Quando uma delas aparece, ele pinta o local na cor escolhida e, se ligado, arrasta o dedo
em linha reta do seu ponto de partida até o alvo (a rota aparece na tela).

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

## Ajustes

- **Sensibilidade**: se não reconhece, baixe; se pinta coisa errada, suba.
- **Qualidade da captura**: menor = mais rápido, maior = mais preciso (vale ao iniciar).
- **Ponto de partida**: onde o dedo começa o arrasto (em % da tela).
- **Velocidade / pausa**: controlam a duração do arrasto e o intervalo entre eles.
- Imagens novas e a qualidade da captura só valem ao iniciar de novo.

## Limitações

- Imagens lisas, sem detalhe (um quadrado de uma cor só), não são reconhecidas.
- O reconhecimento é por comparação de imagem: tamanho muito diferente do original
  (zoom, outra resolução) reduz a precisão.
- O arrasto é em linha reta e o app escolhe o alvo mais perto do ponto de partida.
- Alguns apps e jogos bloqueiam captura de tela ou gestos de acessibilidade.
  Use apenas onde isso for permitido.
