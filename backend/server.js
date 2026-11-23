const express = require('express');
const helmet = require('helmet');
const cors = require('cors');

const app = express();
const PORT = process.env.PORT || 3000;

// Security Middlewares
app.use(helmet());
app.use(cors());
app.use(express.json());

// Basic Heartbeat & Device Integrity Endpoint
app.post('/api/v1/auth/device-validation', (req, res) => {
    const { deviceId, integrityToken, rootStatus } = req.body;
    
    if (rootStatus === true) {
        return res.status(403).json({ error: "Device is rooted, exam blocked." });
    }
    
    res.json({ status: "ok", token: "jwt_token_stub" });
});

// Realtime Monitoring Stub
app.post('/api/v1/exam/heartbeat', (req, res) => {
    // Process heartbeat and cheat signals
    res.json({ ack: true });
});

app.listen(PORT, () => {
    console.log(`Secure CBT Backend running on port ${PORT}`);
});
