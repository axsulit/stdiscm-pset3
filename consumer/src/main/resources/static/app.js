// Video playback state
let currentPlayingVideo = null;
let hoverTimeout = null;
let currentVideos = new Set();

// Modal elements
const modal = document.getElementById('videoModal');
const modalVideo = document.getElementById('modalVideo');
const closeBtn = document.querySelector('.close');

// Close modal when clicking the close button
closeBtn.onclick = function() {
  modalVideo.pause();
  modalVideo.currentTime = 0;
  modal.style.display = "none";
}

// Close modal when clicking outside
window.onclick = function(event) {
  if (event.target == modal) {
    modalVideo.pause();
    modalVideo.currentTime = 0;
    modal.style.display = "none";
  }
}

async function fetchVideos() {
  try {
    const response = await fetch('/list');
    const files = await response.json();
    
    // Check if there are any new videos
    const newFiles = files.filter(file => !currentVideos.has(file));
    
    if (newFiles.length > 0) {
      // Update current videos set
      currentVideos = new Set(files);
      displayVideos(files);
    }
  } catch (error) {
    console.error('Error fetching videos:', error);
  }
}

function displayVideos(files) {
  const container = document.getElementById('videos');
  container.innerHTML = '';

  if (files.length === 0) {
    const placeholder = document.createElement('div');
    placeholder.className = 'empty-queue';
    placeholder.innerHTML = `
      <div class="empty-queue-content">
        <div class="empty-queue-icon">🌱</div>
        <h2>Your queue is green and ready</h2>
        <p>Upload a video to get started</p>
      </div>
    `;
    container.appendChild(placeholder);
    return;
  }

  files.forEach(file => {
    const div = document.createElement('div');
    div.className = 'video-container';
    
    const video = document.createElement('video');
    video.src = `/${file}`;
    video.controls = false;
    video.muted = true;
    video.preload = 'metadata';
    
    // Add title
    const title = document.createElement('div');
    title.className = 'video-title';
    title.textContent = file;
    
    // Handle hover preview
    video.addEventListener('mouseenter', () => {
      // Clear any existing timeout
      if (hoverTimeout) {
        clearTimeout(hoverTimeout);
      }
      
      // Stop any currently playing video
      if (currentPlayingVideo && currentPlayingVideo !== video) {
        currentPlayingVideo.pause();
        currentPlayingVideo.currentTime = 0;
      }
      
      video.currentTime = 0;
      video.play();
      currentPlayingVideo = video;
      
      // Set timeout to stop after 10 seconds
      hoverTimeout = setTimeout(() => {
        if (video === currentPlayingVideo) {
          video.pause();
          video.currentTime = 0;
          currentPlayingVideo = null;
        }
      }, 10000);
    });
    
    // Stop video when mouse leaves
    video.addEventListener('mouseleave', () => {
      if (hoverTimeout) {
        clearTimeout(hoverTimeout);
        hoverTimeout = null;
      }
      video.pause();
      video.currentTime = 0;
      if (currentPlayingVideo === video) {
        currentPlayingVideo = null;
      }
    });
    
    // Handle click to play in modal
    video.addEventListener('click', () => {
      // Stop any currently playing video
      if (currentPlayingVideo && currentPlayingVideo !== video) {
        currentPlayingVideo.pause();
        currentPlayingVideo.currentTime = 0;
      }
      
      // Set up modal video
      modalVideo.src = video.src;
      modalVideo.muted = false;
      
      // Set modal title
      document.getElementById('modalTitle').textContent = file;
      
      // Show modal and play video
      modal.style.display = "block";
      modal.classList.add('fade-in');
      modalVideo.play();
      
      // Reset when video ends
      modalVideo.addEventListener('ended', () => {
        modalVideo.currentTime = 0;
        modalVideo.muted = true;
      }, { once: true });
    });
    
    // Handle loading state
    video.addEventListener('loadstart', () => {
      div.classList.add('loading');
    });
    
    video.addEventListener('canplay', () => {
      div.classList.remove('loading');
    });
    
    // Handle video errors
    video.addEventListener('error', (e) => {
      console.error('Error loading video:', file, e);
      div.classList.add('error');
    });
    
    div.appendChild(video);
    div.appendChild(title);
    container.appendChild(div);
  });
}

// Initial load
fetchVideos();

// Check for new videos every 5 seconds, but only update if there are changes
setInterval(fetchVideos, 5000);
