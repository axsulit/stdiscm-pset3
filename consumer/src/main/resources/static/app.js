// Video playback state
let currentPlayingVideo = null;

async function fetchVideos() {
  try {
    const response = await fetch('/list');
    const files = await response.json();
    displayVideos(files);
  } catch (error) {
    console.error('Error fetching videos:', error);
  }
}

function displayVideos(files) {
  const container = document.getElementById('videos');
  container.innerHTML = '';

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
      if (currentPlayingVideo && currentPlayingVideo !== video) {
        currentPlayingVideo.pause();
        currentPlayingVideo.currentTime = 0;
      }
      video.currentTime = 0;
      video.play();
      currentPlayingVideo = video;
      
      // Stop after 10 seconds
      setTimeout(() => {
        if (video === currentPlayingVideo) {
          video.pause();
          video.currentTime = 0;
          currentPlayingVideo = null;
        }
      }, 10000);
    });
    
    // Handle click to play
    video.addEventListener('click', () => {
      if (currentPlayingVideo && currentPlayingVideo !== video) {
        currentPlayingVideo.pause();
        currentPlayingVideo.currentTime = 0;
      }
      
      video.controls = true;
      video.muted = false;
      video.play();
      currentPlayingVideo = video;
      
      // Reset when video ends
      video.addEventListener('ended', () => {
        video.controls = false;
        video.muted = true;
        currentPlayingVideo = null;
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

// Refresh videos every 5 seconds
setInterval(fetchVideos, 5000);
