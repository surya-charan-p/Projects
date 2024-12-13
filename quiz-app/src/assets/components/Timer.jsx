// import { useEffect, useState } from "react"

// export default function Timer(setStop,questionNumber) {
//     const[timer,setTimer]= useState(30);

//     useEffect(()=>{
//         if(timer === 0) return setStop(true);
//         const interval=setInterval(()=>{
//         setTimer((prev)=>prev-1);
//         },1000);
//         return ()=> clearInterval(interval);
        
//     },[timer,setStop]);

//     useEffect(() => {
//     setTimer(30); // Reset timer every time questionNumber changes
//   }, [questionNumber]);
//   return timer;

// }
import { useEffect, useState } from "react";

export default function Timer({ setStop, questionNumber }) {
  const [timer, setTimer] = useState(30); // Start with 30 seconds

  useEffect(() => {
    // Stop the game when timer reaches 0
    if (timer === 0) {
      setStop(true);
    }
  }, [timer, setStop]);

  useEffect(() => {
    // Reset the timer every time questionNumber changes
    setTimer(30);
  }, [questionNumber]);

  useEffect(() => {
    if (timer === 0) return; // Avoid setting interval if timer is already 0
    const interval = setInterval(() => {
      setTimer((prev) => prev - 1); // Decrease the timer every second
    }, 1000);
    
    return () => clearInterval(interval); // Clear interval on cleanup
  }, [timer]); // This effect runs every time the timer is updated

  return timer; // Return the current timer value
}
