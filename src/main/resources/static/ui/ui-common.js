document.addEventListener('DOMContentLoaded', () => {
  const canvas = document.getElementById('code-rain');
  if (!canvas) {
    return;
  }

  const ctx = canvas.getContext('2d');
  if (!ctx) {
    return;
  }

  let width = 0;
  let height = 0;
  const fontSize = 14;
  let drops = [];

  const resize = () => {
    width = canvas.width = window.innerWidth;
    height = canvas.height = window.innerHeight;
    const columns = Math.max(1, Math.floor(width / fontSize));
    drops = Array(columns).fill(1);
  };

  resize();
  window.addEventListener('resize', resize);

  const chars = '0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ';
  const draw = () => {
    ctx.fillStyle = 'rgba(13, 13, 26, 0.05)';
    ctx.fillRect(0, 0, width, height);

    ctx.fillStyle = '#8b5cf6';
    ctx.font = `${fontSize}px monospace`;

    for (let i = 0; i < drops.length; i += 1) {
      const text = chars[Math.floor(Math.random() * chars.length)];
      ctx.fillText(text, i * fontSize, drops[i] * fontSize);

      if (drops[i] * fontSize > height && Math.random() > 0.975) {
        drops[i] = 0;
      }
      drops[i] += 1;
    }
  };

  setInterval(draw, 35);
});
