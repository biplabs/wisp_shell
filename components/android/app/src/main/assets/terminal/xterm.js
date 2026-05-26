class Terminal {
  constructor(el, input) {
    this.el = el;
    this.input = input;
    this.handlers = [];
    this.el.tabIndex = 0;
    this.el.addEventListener("click", () => this.focus());
    document.body.addEventListener("click", () => this.focus());
    this.input.addEventListener("input", () => {
      if (this.input.value.length > 0) {
        this.emit(this.input.value);
        this.input.value = "";
      }
    });
    this.input.addEventListener("keydown", (event) => {
      if (event.key === "Enter") {
        this.emit("\r");
        event.preventDefault();
      }
      if (event.key === "Backspace") {
        this.emit("\u007f");
        event.preventDefault();
      }
    });
    this.el.addEventListener("keydown", (event) => {
      if (event.key.length === 1) {
        this.emit(event.key);
      }
      if (event.key === "Enter") {
        this.emit("\r");
      }
      if (event.key === "Backspace") {
        this.emit("\u007f");
      }
      event.preventDefault();
    });
    setTimeout(() => this.focus(), 100);
  }
  focus() {
    this.input.focus();
  }
  write(text) {
    this.el.textContent += text;
    window.scrollTo(0, document.body.scrollHeight);
  }
  onData(handler) {
    this.handlers.push(handler);
  }
  emit(data) {
    this.handlers.forEach((handler) => handler(data));
  }
}
