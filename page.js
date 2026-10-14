// Project page behaviour: maths rendering, chart tooltips, BibTeX copy.

document.addEventListener('DOMContentLoaded', () => {
    // KaTeX: \( ... \) inline, \[ ... \] display (pre/code are skipped, so BibTeX stays literal)
    if (window.renderMathInElement) {
        renderMathInElement(document.querySelector('main'), {
            delimiters: [
                { left: '\\[', right: '\\]', display: true },
                { left: '\\(', right: '\\)', display: false },
            ],
            throwOnError: false,
        });
    }

    // Chart tooltips: every mark with data-tip-value shows its value on hover and on keyboard focus.
    const tip = document.querySelector('.chart-tip');
    const show = (el, x, y) => {
        tip.replaceChildren();
        const value = document.createElement('strong');
        value.textContent = el.dataset.tipValue;
        const label = document.createElement('span');
        label.textContent = el.dataset.tipLabel;
        tip.append(value, label);
        tip.hidden = false;
        const r = tip.getBoundingClientRect();
        const left = Math.min(Math.max(8, x + 14), window.innerWidth - r.width - 8);
        const top = y - r.height - 12 < 8 ? y + 16 : y - r.height - 12;
        tip.style.left = `${left}px`;
        tip.style.top = `${top}px`;
    };
    const hide = () => { tip.hidden = true; };
    document.querySelectorAll('[data-tip-value]').forEach((el) => {
        el.addEventListener('pointermove', (e) => show(el, e.clientX, e.clientY));
        el.addEventListener('pointerleave', hide);
        el.addEventListener('focus', () => {
            const b = el.getBoundingClientRect();
            show(el, b.left + b.width / 3, b.top);
        });
        el.addEventListener('blur', hide);
    });

    // Copy BibTeX
    const button = document.querySelector('.copy-bibtex');
    const code = document.querySelector('#bibtex-code');
    if (button && code) {
        button.addEventListener('click', async () => {
            try {
                await navigator.clipboard.writeText(code.textContent);
                button.textContent = 'Copied';
            } catch (err) {
                const range = document.createRange();
                range.selectNodeContents(code);
                const sel = window.getSelection();
                sel.removeAllRanges();
                sel.addRange(range);
                button.textContent = 'Selected: press Ctrl/Cmd+C';
            }
            setTimeout(() => { button.textContent = 'Copy BibTeX'; }, 2000);
        });
    }
});
