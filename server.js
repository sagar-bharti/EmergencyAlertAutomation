import express from 'express';

const app = express();
app.use(express.json());

app.get('/', (req, res) => {
  res.json({ message: "AI Server running!", status: "ok" });
});

app.get('/health', (req, res) => {
  res.json({ status: "ok" });
});

app.post('/analyze', (req, res) => {
  res.json({
    emergency: true,
    text: "help"
  });
});

const PORT = process.env.PORT || 10000;
app.listen(PORT, () => {
  console.log(`Server running on port ${PORT}`);
});
