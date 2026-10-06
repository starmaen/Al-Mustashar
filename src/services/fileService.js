import { ref, uploadBytes, getDownloadURL } from 'firebase/storage';
import { storage, db } from '../config/firebase';
import { doc, setDoc, collection } from 'firebase/firestore';

export async function uploadFromDrive(driveFileId, userId, metadata) {
  try {
    const response = await fetch(
      `https://www.googleapis.com/drive/v3/files/${driveFileId}?alt=media`,
      {
        headers: {
          Authorization: `Bearer ${metadata.driveAccessToken}`,
        },
      }
    );

const blob = await response.blob();
    const fileName = `legal-docs/${userId}/${Date.now()}_${metadata.fileName}`;
    const storageRef = ref(storage, fileName);
    
    const uploadResult = await uploadBytes(storageRef, blob, {
      contentType: metadata.mimeType,
      customMetadata: {
        driveFileId: driveFileId,
        originalName: metadata.fileName,
        uploadedBy: userId,
      },
    });

const downloadURL = await getDownloadURL(uploadResult.ref);

const docRef = doc(collection(db, 'legalDocuments'));
    await setDoc(docRef, {
      fileName: metadata.fileName,
      fileSize: blob.size,
      storagePath: fileName,
      downloadURL: downloadURL,
      driveFileId: driveFileId,
      userId: userId,
      uploadedAt: new Date().toISOString(),
    });

return { success: true, documentId: docRef.id, downloadURL };
  } catch (error) {
    console.error('خطأ في رفع الملف:', error);
    throw error;
  }
}
