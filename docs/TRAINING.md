# Nova — Model Training Guide

> How to fine-tune and retrain the Nova companion model using Unsloth + LoRA on cloud GPUs.

---

## Overview

Nova's brain is a fine-tuned **Nanbeige4.1-3B** model, quantized to **Q4_K_M** GGUF format for on-device inference. Training uses **Unsloth** for 2x faster LoRA fine-tuning with 70% less memory.

---

## 1. GPU Setup (Brev.dev)

### Recommended Instances

| GPU             | VRAM  | Cost/hr  | Training Time (1 epoch, 10k samples) |
|-----------------|-------|----------|---------------------------------------|
| NVIDIA L4       | 24 GB | ~$0.50   | ~45 min                               |
| RTX PRO 6000    | 48 GB | ~$1.20   | ~25 min                               |

### Brev Setup

```bash
# 1. Create a Brev instance
brev create nova-training --gpu l4 --image pytorch:2.2.0-cuda12.1

# 2. SSH in
brev open nova-training

# 3. Install dependencies
pip install "unsloth[colab-new] @ git+https://github.com/unslothai/unsloth.git"
pip install --no-deps trl peft accelerate bitsandbytes
pip install datasets
```

Alternatively, use any machine with an NVIDIA GPU (24GB+ VRAM), CUDA 12.1+, and PyTorch 2.2+.

---

## 2. Dataset Format

Training data is in **JSONL** format. Each line is a JSON object with `instruction` and `response` fields.

### File: `dataset.jsonl`

```jsonl
{"instruction": "Hey Nova, how's it going?", "response": "Hey! I'm doing great. I was actually just thinking about you — how did that presentation go yesterday? You mentioned you were nervous about it."}
{"instruction": "I'm feeling really stressed about work lately.", "response": "I hear you. Work stress can be so draining, especially when it piles up. Want to talk through what's going on? Sometimes just getting it out helps. Or if you'd rather take your mind off it, I'm here for that too."}
{"instruction": "What should I eat for dinner?", "response": "Hmm, let me think... You mentioned last week you're trying to hit your protein goals. How about a quick chicken stir-fry? You could do chicken breast, broccoli, bell peppers, and some rice. Simple, high protein, and ready in like 20 minutes. Want me to walk you through it?"}
{"instruction": "I just finished a really hard workout.", "response": "Let's go! How are you feeling? That post-workout high is unmatched. What did you hit today? And make sure you're getting some protein in within the next hour — your muscles are ready for it."}
```

### Dataset Guidelines

- **Tone**: Warm, natural, conversational. Nova has personality — not corporate.
- **Memory references**: Include examples where Nova references past conversations ("You mentioned...", "Last time you said...")
- **Proactivity**: Include examples where Nova asks follow-up questions and shows genuine curiosity
- **Conciseness**: Responses should be 1-3 sentences typically. Not essays.
- **Variety**: Cover daily life, emotions, goals, health, work, relationships, hobbies
- **Target size**: 5,000–15,000 high-quality examples

### Dataset Preparation Script

```python
import json

def prepare_dataset(input_file, output_file):
    """Convert raw JSONL to Unsloth-compatible format."""
    formatted = []
    with open(input_file, 'r') as f:
        for line in f:
            entry = json.loads(line.strip())
            formatted.append({
                "text": format_prompt(entry["instruction"], entry["response"])
            })
    
    with open(output_file, 'w') as f:
        for item in formatted:
            f.write(json.dumps(item) + '\n')
    
    print(f"Prepared {len(formatted)} training examples")

def format_prompt(instruction, response):
    return f"""### System
You are Nova, a personal AI companion. You are warm, curious, and genuinely interested in the user's life. You remember past conversations and bring them up naturally. You are proactive — you check in, ask follow-up questions, and notice patterns. You speak concisely but with personality. Never be robotic.

### User
{instruction}

### Assistant
{response}"""

if __name__ == "__main__":
    prepare_dataset("dataset.jsonl", "train_data.jsonl")
```

---

## 3. Training Code (Unsloth + LoRA)

### `train.py`

```python
import torch
from unsloth import FastLanguageModel
from trl import SFTTrainer
from transformers import TrainingArguments
from datasets import load_dataset

# ============================================================
# 1. Load Base Model
# ============================================================

model, tokenizer = FastLanguageModel.from_pretrained(
    model_name="Nanbeige/Nanbeige4.1-3B",
    max_seq_length=4096,
    dtype=None,           # auto-detect (float16 on L4, bfloat16 on A100)
    load_in_4bit=True,    # QLoRA — fits in 24GB VRAM
)

# ============================================================
# 2. Configure LoRA
# ============================================================

model = FastLanguageModel.get_peft_model(
    model,
    r=64,                          # LoRA rank (higher = more capacity, more VRAM)
    target_modules=[
        "q_proj", "k_proj", "v_proj", "o_proj",
        "gate_proj", "up_proj", "down_proj",
    ],
    lora_alpha=64,                 # scaling factor (usually same as r)
    lora_dropout=0.05,
    bias="none",
    use_gradient_checkpointing="unsloth",  # 60% less VRAM
    random_state=42,
)

# ============================================================
# 3. Load Dataset
# ============================================================

dataset = load_dataset("json", data_files="train_data.jsonl", split="train")

# ============================================================
# 4. Training Configuration
# ============================================================

trainer = SFTTrainer(
    model=model,
    tokenizer=tokenizer,
    train_dataset=dataset,
    dataset_text_field="text",
    max_seq_length=4096,
    dataset_num_proc=2,
    packing=True,                  # pack multiple short examples into one sequence
    args=TrainingArguments(
        per_device_train_batch_size=4,
        gradient_accumulation_steps=4,   # effective batch size = 16
        warmup_steps=50,
        num_train_epochs=3,
        learning_rate=2e-4,
        fp16=not torch.cuda.is_bf16_supported(),
        bf16=torch.cuda.is_bf16_supported(),
        logging_steps=10,
        optim="adamw_8bit",
        weight_decay=0.01,
        lr_scheduler_type="cosine",
        seed=42,
        output_dir="outputs",
        save_steps=100,
        save_total_limit=3,
    ),
)

# ============================================================
# 5. Train
# ============================================================

print("Starting training...")
trainer_stats = trainer.train()
print(f"Training complete! Loss: {trainer_stats.training_loss:.4f}")

# ============================================================
# 6. Save LoRA Adapter
# ============================================================

model.save_pretrained("nova-lora-adapter")
tokenizer.save_pretrained("nova-lora-adapter")
print("LoRA adapter saved to nova-lora-adapter/")
```

### Run Training

```bash
python train.py
```

Expected output on L4 (24GB):
- ~45 min for 3 epochs on 10k samples
- Peak VRAM: ~18 GB
- Final loss: ~0.8-1.2 (depending on dataset quality)

---

## 4. Export as GGUF

After training, merge the LoRA adapter back into the base model and convert to GGUF for llama.cpp.

### `export_gguf.py`

```python
from unsloth import FastLanguageModel

# Load the trained model with LoRA adapter
model, tokenizer = FastLanguageModel.from_pretrained(
    model_name="nova-lora-adapter",
    max_seq_length=4096,
    dtype=None,
    load_in_4bit=False,   # load in full precision for export
)

# Merge LoRA weights into base model
model = model.merge_and_unload()

# Save merged model in HuggingFace format
model.save_pretrained_merged(
    "nova-merged",
    tokenizer,
    save_method="merged_16bit",
)

print("Merged model saved. Now convert to GGUF:")
print("  python llama.cpp/convert_hf_to_gguf.py nova-merged --outfile nova-3b-f16.gguf --outtype f16")
print("  ./llama.cpp/llama-quantize nova-3b-f16.gguf nova-3b-q4km.gguf Q4_K_M")
```

### GGUF Conversion & Quantization

```bash
# Clone llama.cpp if not already present
git clone https://github.com/ggerganov/llama.cpp.git
cd llama.cpp && make -j$(nproc) && cd ..

# Convert HF model to GGUF (FP16)
python llama.cpp/convert_hf_to_gguf.py nova-merged \
    --outfile nova-3b-f16.gguf \
    --outtype f16

# Quantize to Q4_K_M (~2.3GB)
./llama.cpp/llama-quantize nova-3b-f16.gguf nova-3b-q4km.gguf Q4_K_M

# Verify the model works
./llama.cpp/llama-cli -m nova-3b-q4km.gguf \
    -p "### System\nYou are Nova.\n\n### User\nHey, how are you?\n\n### Assistant\n" \
    -n 100 --temp 0.7 --top-p 0.9 --repeat-penalty 1.1
```

### Quantization Options

| Quant    | Size    | Quality | Speed   | Recommended          |
|----------|---------|---------|---------|----------------------|
| Q4_K_M   | ~2.3 GB | Good    | Fast    | Default (best balance)|
| Q5_K_M   | ~2.7 GB | Better  | Slower  | If storage allows     |
| Q8_0     | ~3.5 GB | Best    | Slowest | Testing only          |
| Q3_K_S   | ~1.8 GB | Lower   | Fastest | Low-end devices       |

---

## 5. System Prompt & Prompt Template

### System Prompt (used during both training and inference)

```
You are Nova, a personal AI companion. You are warm, curious, and genuinely 
interested in the user's life. You remember past conversations and bring them 
up naturally. You are proactive — you check in, ask follow-up questions, and 
notice patterns. You speak concisely but with personality. Never be robotic.
```

### Prompt Template

The exact template used at inference time. Training data MUST match this format.

```
### System
{system_prompt}

Context from memory:
{memory_context}

Today's date: {date}
Time: {time}

### User
{user_message}

### Assistant
{response}
```

### Important Notes

- **Delimiter tokens**: `### System`, `### User`, `### Assistant` are the role delimiters. Add these as stop tokens during inference.
- **Memory context** is injected at inference time only — training examples don't include it (the model learns to use whatever context is in the system block).
- **Keep responses short** in training data. Nova should feel like texting a friend, not reading an essay.
- **Include personality markers**: Casual language, occasional humor, genuine empathy, natural follow-up questions.

---

## 6. Retraining Workflow

When you want to improve Nova:

1. **Collect feedback**: Note conversations where Nova's responses felt off
2. **Create correction pairs**: Write the ideal response for each bad case
3. **Add to dataset**: Append new examples to `dataset.jsonl`
4. **Retrain**: Run `train.py` (can use previous checkpoint for faster convergence)
5. **Export**: Run `export_gguf.py` + quantization
6. **Test**: Run local inference to validate
7. **Deploy**: Replace the GGUF file in the app's model directory
8. **Version**: Tag the model with a version number (e.g., `nova-v1.1-q4km.gguf`)

### Version Naming Convention

```
nova-v{major}.{minor}-{quant}.gguf

Examples:
  nova-v1.0-q4km.gguf    # Initial release
  nova-v1.1-q4km.gguf    # Personality refinements
  nova-v2.0-q4km.gguf    # New base model or major dataset overhaul
```
