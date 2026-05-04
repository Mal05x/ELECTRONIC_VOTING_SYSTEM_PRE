# Use a lightweight Python base image
FROM python:3.10-slim

# Install the system-level C++ libraries required by OpenCV and MediaPipe
RUN apt-get update && apt-get install -y \
    libgl1 \
    libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

# Set the working directory inside the container
WORKDIR /app

# Copy the requirements file and install Python libraries
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy your actual Python code into the container
COPY . .

# Expose port 5001 (matching the port in your app.py)
EXPOSE 5001

# The command to boot the AI server
CMD ["python", "app.py"]