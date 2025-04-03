/**
 * Video playback state management
 */
let currentPlayingVideo = null;
let hoverTimeout = null;
let currentVideos = new Set();

/**
 * DOM Elements
 */
const modal = document.getElementById('videoModal');
const modalVideo = document.getElementById('modalVideo');
const closeBtn = document.querySelector('.close');

/**
 * Modal Event Handlers
 */
closeBtn.onclick = () => {
    modalVideo.pause();
    modalVideo.currentTime = 0;
    modal.style.display = "none";
};

window.onclick = (event) => {
    if (event.target === modal) {
        modalVideo.pause();
        modalVideo.currentTime = 0;
        modal.style.display = "none";
    }
};

/**
 * Fetches the list of videos from the server
 * Only updates the display if the list has changed
 */
async function fetchVideos() {
    try {
        const response = await fetch('/list');
        const files = await response.json();
        
        // Check if the video list has changed
        const newFiles = new Set(files);
        const hasChanged = files.length !== currentVideos.size || 
                          files.some(file => !currentVideos.has(file)) ||
                          Array.from(currentVideos).some(file => !newFiles.has(file));
        
        if (hasChanged) {
            currentVideos = newFiles;
            displayVideos(files);
        }
    } catch (error) {
        console.error('Error fetching videos:', error);
        displayVideos([]);
    }
}

/**
 * Displays the list of videos in the grid
 * @param {string[]} files - Array of video filenames
 */
function displayVideos(files) {
    const container = document.getElementById('videos');
    
    // Handle empty state
    if (files.length === 0) {
        if (!container.querySelector('.empty-queue')) {
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
        }
        return;
    }

    // Remove empty state if it exists
    const placeholder = container.querySelector('.empty-queue');
    if (placeholder) {
        placeholder.remove();
    }

    // Clear existing video containers
    const videoContainers = container.querySelectorAll('.video-container');
    videoContainers.forEach(container => container.remove());

    // Create new video containers
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
            if (hoverTimeout) {
                clearTimeout(hoverTimeout);
            }
            
            if (currentPlayingVideo && currentPlayingVideo !== video) {
                currentPlayingVideo.pause();
                currentPlayingVideo.currentTime = 0;
            }
            
            video.currentTime = 0;
            video.play();
            currentPlayingVideo = video;
            
            hoverTimeout = setTimeout(() => {
                if (video === currentPlayingVideo) {
                    video.pause();
                    video.currentTime = 0;
                    currentPlayingVideo = null;
                }
            }, 10000);
        });
        
        // Stop video on mouse leave
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
            if (currentPlayingVideo && currentPlayingVideo !== video) {
                currentPlayingVideo.pause();
                currentPlayingVideo.currentTime = 0;
            }
            
            modalVideo.src = video.src;
            modalVideo.muted = false;
            document.getElementById('modalTitle').textContent = file;
            
            modal.style.display = "block";
            modal.classList.add('fade-in');
            modalVideo.play();
            
            modalVideo.addEventListener('ended', () => {
                modalVideo.currentTime = 0;
                modalVideo.muted = true;
            }, { once: true });
        });
        
        // Handle loading states
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

// Initialize the application
fetchVideos();

// Poll for new videos every 5 seconds
setInterval(fetchVideos, 5000);
